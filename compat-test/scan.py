#!/usr/bin/env python3
"""Switchboard Gboard 兼容性扫描器 v2。

对若干 Gboard APK 做静态 DEX 扫描，验证 Switchboard 各 hook 锚点在该版本是否存在。
不执行任何 APK 代码。

v2: 解析 class_defs → class_data，获得**定义级**方法/字段表（此前只查字符串池，
无法区分"类存在"与"方法存在"）。锚点分四级：
  str    — 字符串池原文（DexKit usingStrings 前置条件）
  cls    — 类型描述符（如 Ladbx;）
  method — 类描述符 + 方法名（定义级）
  field  — 类描述符 + 字段名（定义级）

用法:
    python scan.py [目录或 apk 路径 ...]
默认扫描 ./apks 下所有 .apk。输出兼容性矩阵到 stdout 和 compat-report.json。
"""
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

# ---------- 锚点定义 ----------
ANCHORS = [
    ("flag_reader", "str", "Invalid flag: ", "FlagEngine: DexKit 泛型 flag 读取器特征串"),
    ("flag_reader2", "str", "Invalid flag:", "FlagEngine: fallback 特征串"),
    ("retention", "str", "Failed to parse retention hours", "Clipboard: 保存时长解析方法特征串"),
    ("clipboard_provider", "cls", "Lcom/google/android/apps/inputmethod/libs/clipboard/ClipboardContentProvider;", "Clipboard: 剪贴板 Provider 类"),
    ("softkey_view", "cls", "Lcom/google/android/libraries/inputmethod/widgets/SoftKeyView;", "通用: 键位视图（双拼/译词/九键键面）"),
    ("softkey_def", "cls", "Lcom/google/android/libraries/inputmethod/metadata/SoftKeyDef;", "通用: 键位元数据"),
    ("number_mode_kb", "cls", "Lcom/google/android/apps/inputmethod/latin/keyboard/LatinKeyboardWithNumberMode;", "九键标点: 数字页键盘类"),
    ("keyboard_state_method", "method", ("Lcom/google/android/libraries/inputmethod/keyboard/impl/Keyboard;", "cF"), "九键标点: 状态变更方法 cF"),
    ("keyboard_state_field", "field", ("Lcom/google/android/libraries/inputmethod/keyboard/impl/Keyboard;", "F"), "九键标点: 状态字段 F"),
    ("keyboard_name_method", "method", ("Lcom/google/android/libraries/inputmethod/keyboard/impl/Keyboard;", "cz"), "九键标点: 键盘名称方法 cz"),
    ("commit_wrapper", "cls", "Laekp;", "九键标点: commitText 包装兜底类 (18.5.4)"),
    ("commit_wrapper_method", "method", ("Laekp;", "q"), "九键标点: 包装类提交方法 q"),
    ("strip_holder", "cls", "Lcom/google/android/libraries/inputmethod/keyboard/widget/ReadingTextCandidateHolderView;", "九键标点: 标点栏容器"),
    ("autoscaletv", "cls", "Lcom/google/android/libraries/inputmethod/widgets/text/AutoCenterScaleTextView;", "Emoji 提亮: 候选文本视图"),
]

FLAG_CLASS_CANDIDATES = ["adbx", "odx", "obw", "acps", "nxw", "ajoh"]  # FuckGboard 各版本 flag 类


def read_uleb(dex: bytes, off: int):
    result = 0
    shift = 0
    while off < len(dex):
        b = dex[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


def read_dex_string_list(dex: bytes):
    """返回按索引对齐的字符串列表（method/field 名解析需要索引）。"""
    if len(dex) < 112 or dex[:4] != b"dex\n":
        return []
    string_ids_size, string_ids_off = struct.unpack_from("<II", dex, 56)
    out = []
    for i in range(string_ids_size):
        (off,) = struct.unpack_from("<I", dex, string_ids_off + i * 4)
        n, p = read_uleb(dex, off)
        end = dex.find(b"\x00", p)
        out.append(dex[p:end].decode("utf-8", "replace") if end > p else "")
    return out


def parse_dex_symbols(dex: bytes):
    """解析一个 dex，返回 (字符串集合, 类定义表)。

    类定义表: {类描述符: {"methods": set(方法名), "fields": set(字段名)}}
    来源是 class_defs → class_data（定义级，而非引用级）。
    field_idx / method_idx 索引的是 field_ids / method_ids 表，
    名字需经 name_idx 间接解析到字符串池。
    """
    strings = read_dex_string_list(dex)
    str_set = set(strings)
    if not strings:
        return str_set, {}
    if len(dex) < 112 or dex[:4] != b"dex\n":
        return str_set, {}

    def name_of(ids_off: int, ids_size: int, idx: int) -> str:
        if idx >= ids_size:
            return ""
        # id 项: class_idx u16 + (type_idx|proto_idx) u16 + name_idx u32
        (name_idx,) = struct.unpack_from("<I", dex, ids_off + idx * 8 + 4)
        return strings[name_idx] if name_idx < len(strings) else ""

    field_ids_size, field_ids_off = struct.unpack_from("<II", dex, 80)
    method_ids_size, method_ids_off = struct.unpack_from("<II", dex, 88)

    def field_name(idx: int) -> str:
        return name_of(field_ids_off, field_ids_size, idx)

    def method_name(idx: int) -> str:
        return name_of(method_ids_off, method_ids_size, idx)

    type_ids_size, type_ids_off = struct.unpack_from("<II", dex, 64)
    type_desc = []
    for i in range(type_ids_size):
        (desc_idx,) = struct.unpack_from("<I", dex, type_ids_off + i * 4)
        type_desc.append(strings[desc_idx] if desc_idx < len(strings) else "")

    classes = {}
    class_defs_size, class_defs_off = struct.unpack_from("<II", dex, 96)
    for i in range(class_defs_size):
        base = class_defs_off + i * 32
        class_idx, _acc, _super, _ifc, _src, _ann, class_data_off, _sv = struct.unpack_from(
            "<8I", dex, base
        )
        desc = type_desc[class_idx] if class_idx < len(type_desc) else ""
        if not desc or class_data_off == 0 or class_data_off >= len(dex):
            continue
        entry = {"methods": set(), "fields": set()}
        p = class_data_off
        static_f, p = read_uleb(dex, p)
        instance_f, p = read_uleb(dex, p)
        direct_m, p = read_uleb(dex, p)
        virtual_m, p = read_uleb(dex, p)

        idx = 0
        for _ in range(static_f):
            diff, p = read_uleb(dex, p)
            _, p = read_uleb(dex, p)
            idx += diff
            n = field_name(idx)
            if n:
                entry["fields"].add(n)
        idx = 0
        for _ in range(instance_f):
            diff, p = read_uleb(dex, p)
            _, p = read_uleb(dex, p)
            idx += diff
            n = field_name(idx)
            if n:
                entry["fields"].add(n)
        idx = 0
        for _ in range(direct_m):
            diff, p = read_uleb(dex, p)
            _, p = read_uleb(dex, p)
            _, p = read_uleb(dex, p)  # code_off
            idx += diff
            n = method_name(idx)
            if n:
                entry["methods"].add(n)
        idx = 0
        for _ in range(virtual_m):
            diff, p = read_uleb(dex, p)
            _, p = read_uleb(dex, p)
            _, p = read_uleb(dex, p)
            idx += diff
            n = method_name(idx)
            if n:
                entry["methods"].add(n)

        classes[desc] = entry
    return str_set, classes


def scan_apk(path: Path) -> dict:
    info = {"apk": str(path), "anchors": {}, "flag_classes": []}
    strings_total = set()
    classes_total: dict = {}

    try:
        with zipfile.ZipFile(path) as z:
            names = [n for n in z.namelist() if re.match(r"classes\d*\.dex$", n)]
            for n in names:
                s, c = parse_dex_symbols(z.read(n))
                strings_total |= s
                for k, v in c.items():
                    if k in classes_total:
                        classes_total[k]["methods"] |= v["methods"]
                        classes_total[k]["fields"] |= v["fields"]
                    else:
                        classes_total[k] = {"methods": set(v["methods"]), "fields": set(v["fields"])}
        info["dex_files"] = len(names)
    except Exception as e:  # noqa: BLE001
        info["error"] = str(e)
        return info

    for name, kind, needle, desc in ANCHORS:
        if kind == "str":
            info["anchors"][name] = needle in strings_total
        elif kind == "cls":
            info["anchors"][name] = needle in classes_total
        elif kind == "method":
            cls, m = needle
            info["anchors"][name] = m in classes_total.get(cls, {}).get("methods", set())
        elif kind == "field":
            cls, f = needle
            info["anchors"][name] = f in classes_total.get(cls, {}).get("fields", set())

    info["flag_classes"] = [c for c in FLAG_CLASS_CANDIDATES if f"L{c};" in classes_total]
    return info


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    targets = sys.argv[1:] or ["apks"]
    apks = []
    for t in targets:
        p = Path(t)
        if p.is_dir():
            apks.extend(sorted(p.rglob("*.apk")))
        elif p.is_file():
            apks.append(p)
    if not apks:
        print("未找到 APK。把 Gboard apk 放进 compat-test/apks/<版本>/ 后重试。")
        return

    reports = []
    for apk in apks:
        print(f"\n=== {apk} ===")
        r = scan_apk(apk)
        reports.append(r)
        if "error" in r:
            print(f"  解析失败: {r['error']}")
            continue
        for name, _k, _n, desc in ANCHORS:
            mark = "✓" if r["anchors"][name] else "✗"
            print(f"  {mark} {name:24} {desc}")
        print(f"  flag 类候选命中: {r['flag_classes'] or '无'}")

    out = Path(__file__).parent / "compat-report.json"
    out.write_text(json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n报告已写入 {out}")


if __name__ == "__main__":
    main()
