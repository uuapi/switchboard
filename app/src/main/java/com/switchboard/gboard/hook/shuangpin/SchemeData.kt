/*
 * Ported from fcitx/libime shuangpindata.h and pinyindata.cpp via
 * gboard-shuangpin-hints SchemeData.java.
 * Copyright 2011-2020 CSSlayer <wengxt@gmail.com>
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package com.switchboard.gboard.hook.shuangpin

import java.util.Collections

/**
 * 双拼方案数据（小鹤/自然码/MS/ABC/紫光/拼音加加）。
 */
internal object SchemeData {
    val SYLLABLES: Set<String> = Collections.unmodifiableSet(
        ("a ai an ang ao ba bai ban bang bao bei ben beng bi bian biang biao bie bin bing bo bong bu " +
            "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu " +
            "chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao " +
            "de dei den deng di dia dian diao die din ding diu dong dou du duan dui dun duo e ei en eng " +
            "er fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu " +
            "gua guai guan guang gui gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai huan " +
            "huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan " +
            "kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao le " +
            "lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve m ma mai " +
            "man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu n na nai nan nang nao " +
            "ne nei nen neng ng ni nia nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv nve " +
            "o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang " +
            "qiao qie qin qing qiong qiu qu quan que qun r ran rang rao re ren reng ri rong rou ru rua " +
            "ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shei shen " +
            "sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo ta " +
            "tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan " +
            "wang wei wen weng wo wong wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
            "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen " +
            "zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang " +
            "zhui zhun zhuo zi zong zou zu zuan zui zun zuo").split(" ").toSet()
    )

    private val FINALS = HashMap<String, Map<Char, List<String>>>()
    private val INITIALS = HashMap<String, Map<Char, String>>()

    init {
        register(
            "XIAOHE",
            "d:ai j:an h:ang c:ao w:ei f:en g:eng x:ia m:ian l:iang n:iao p:ie b:in k:ing s:iong q:iu s:ong z:ou x:ua k:uai r:uan l:uang t:ue v:ui y:un o:uo t:ve v:v",
            "i:ch u:sh v:zh",
        )
        register(
            "Ziranma",
            "l:ai j:an h:ang k:ao z:ei f:en g:eng r:er w:ia m:ian d:iang c:iao x:ie n:in y:ing s:iong q:iu s:ong b:ou w:ua y:uai r:uan d:uang t:ue v:ui p:un o:uo t:ve v:v",
            "i:ch u:sh v:zh",
        )
        register(
            "MS",
            "l:ai j:an h:ang k:ao z:ei f:en g:eng r:er w:ia m:ian d:iang c:iao x:ie n:in ;:ing s:iong q:iu s:ong b:ou w:ua y:uai r:uan d:uang t:ue v:ui p:un o:uo v:ve y:v",
            "i:ch u:sh v:zh",
        )
        register(
            "ABC",
            "l:ai j:an h:ang k:ao q:ei f:en g:eng r:er d:ia w:ian t:iang z:iao x:ie c:in y:ing s:iong r:iu s:ong b:ou d:ua c:uai p:uan t:uang m:ue m:ui n:un o:uo m:ve v:v",
            "e:ch v:sh a:zh",
        )
        register(
            "Ziguang",
            "p:ai r:an s:ang q:ao k:ei w:en t:eng j:er x:ia f:ian g:iang b:iao d:ie y:in ;:ing h:iong j:iu h:ong z:ou x:ua y:uai l:uan g:uang n:ue n:ui m:un o:uo n:ve v:v",
            "a:ch i:sh u:zh",
        )
        register(
            "PinyinJiaJia",
            "s:ai f:an g:ang d:ao w:ei r:en t:eng q:er b:ia j:ian h:iang k:iao m:ie l:in q:ing y:iong n:iu y:ong p:ou b:ua x:uai c:uan h:uang x:ue v:ui z:un o:uo x:ve v:v",
            "u:ch i:sh v:zh",
        )
    }

    private fun register(name: String, finals: String, initials: String) {
        val f = LinkedHashMap<Char, MutableList<String>>()
        for (entry in finals.split(" ")) {
            val key = entry[0]
            f.getOrPut(key) { ArrayList() }.add(entry.substring(2))
        }
        for (key in "aeiou".toCharArray()) {
            f.getOrPut(key) { ArrayList() }.add(key.toString())
        }
        val i = HashMap<Char, String>()
        for (entry in initials.split(" ")) i[entry[0]] = entry.substring(2)
        FINALS[name] = f
        INITIALS[name] = i
    }

    fun finals(scheme: String?): Map<Char, List<String>> =
        FINALS[scheme] ?: emptyMap()

    fun initial(scheme: String?, key: Char): String? =
        INITIALS[scheme]?.get(key.lowercaseChar())
}
