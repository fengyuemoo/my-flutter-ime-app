package com.example.myapp.dict.impl

/**
 * 拼音表（用于：预览切分、最长前缀匹配、T9/全键盘分段等）。
 *
 * 注意：
 * - 这里既包含项目历史沿用的一些“非标准/前缀友好”的条目（例如 bia/yian 等），也包含标准普通话拼音音节的完整集合。
 * - 为了避免后续再出现 “候选是 huan 但预览变成 hu'an” 这类问题，标准拼音音节在本文件末尾统一补全。
 */
object PinyinTable {
    val allPinyins: List<String> = listOf(
        // ---- 仓库原有表：保持不删不改（避免回归） ----
        "zhuang","shuang","chuang","jiang","qiang","xiang","xiong","liang","niang","guang","kuang","huang",
        "zhang","shang","chang","zheng","sheng","cheng","zhong","chong","shong","zhuan","shuan","chuan",
        "beng","peng","meng","feng","deng","teng","neng","leng","geng","keng","heng","zeng","ceng","seng",
        "bang","pang","mang","fang","dang","tang","nang","lang","gang","kang","hang","zang","cang","sang",
        "bing","ping","ming","ding","ting","ning","ling","jing","qing","xing","ying",
        "dong","tong","nong","long","gong","kong","hong","zong","cong","song","yong",
        "bian","pian","mian","dian","tian","nian","lian","jian","qian","xian","yian",
        "biao","piao","miao","diao","tiao","niao","liao","jiao","qiao","xiao","yiao",
        "duan","tuan","nuan","luan","juan","quan","xuan","yuan",
        "zhai","shai","chai","zhei","shei","chei","zhui","shui","chui","zhou","shou","chou","zhan","shan","chan","zhen","shen","chen","zhun","shun","chun",
        "bia","dia","nia","lia","jia","qia","xia","nve","lue","jue","que","xue","yue",
        "ban","pan","man","fan","dan","tan","nan","lan","gan","kan","han","zan","can","san","ran","yan","wan",
        "ben","pen","men","fen","den","nen","len","gen","ken","hen","zen","cen","sen","ren","wen",
        "bin","pin","min","lin","jin","qin","xin","yin",
        "bei","pei","mei","fei","dei","nei","lei","gei","kei","hei","zei","cei","sei","wei",
        "bao","pao","mao","dao","tao","nao","lao","gao","kao","hao","zao","cao","sao","rao","yao",
        "bai","pai","mai","dai","tai","nai","lai","gai","kai","hai","zai","cai","sai","wai",
        "bou","pou","mou","fou","dou","tou","nou","lou","gou","kou","hou","zou","cou","sou","you",
        "die","tie","nie","lie","jie","qie","xie","yie",
        "duo","tuo","nuo","luo","guo","kuo","huo","zuo","cuo","suo","ruo",
        "bie","pie","mie","da","ta","na","la","ga","ka","ha","za","ca","sa","ba","pa","ma","fa","ra","ya","wa",
        "de","te","ne","le","ge","ke","he","ze","ce","se","re","ye",
        "di","ti","ni","li","ji","qi","xi","yi",
        "bu","pu","mu","fu","du","tu","nu","lu","gu","ku","hu","zu","cu","su","ru","yu","wu",
        "bo","po","mo","fo","lo","yo","wo",
        "nv","lv","ju","qu","xu",
        "zi","ci","si","ri","zhi","chi","shi",
        "ai","ei","ui","ao","ou","iu","ie","ue","er","an","en","in","un","vn","ang","eng","ing","ong",
        "a","o","e",

        // --- 补全：标准拼音音节（按 initials+finals 组合表追加；ü 系统一律用 v 表示） ---
        // a / o / e
        "a","ba","pa","ma","fa","da","ta","na","la","ga","ka","ha","za","ca","sa","zha","cha","sha",
        "o","bo","po","mo","fo",
        "e","me","de","te","ne","le","ge","ke","he","ze","ce","se","zhe","che","she","re",

        // ai / ei / ao / ou
        "ai","bai","pai","mai","dai","tai","nai","lai","gai","kai","hai","zai","cai","sai","zhai","chai","shai",
        "ei","bei","pei","mei","fei","dei","tei","nei","lei","gei","kei","hei","zei","zhei","shei",
        "ao","bao","pao","mao","dao","tao","nao","lao","gao","kao","hao","zao","cao","sao","zhao","chao","shao","rao",
        "ou","pou","mou","fou","dou","tou","nou","lou","gou","kou","hou","zou","cou","sou","zhou","chou","shou","rou",

        // an / ang / en / eng / ong
        "an","ban","pan","man","fan","dan","tan","nan","lan","gan","kan","han","zan","can","san","zhan","chan","shan","ran",
        "ang","bang","pang","mang","fang","dang","tang","nang","lang","gang","kang","hang","zang","cang","sang","zhang","chang","shang","rang",
        "en","ben","pen","men","fen","den","nen","gen","ken","hen","zen","cen","sen","zhen","chen","shen","ren",
        "eng","beng","peng","meng","feng","deng","teng","neng","leng","geng","keng","heng","zeng","ceng","seng","zheng","cheng","sheng","reng",
        "ong","dong","tong","nong","long","gong","kong","hong","zong","cong","song","zhong","chong","rong",

        // u 系（含 w-）
        "wu","bu","pu","mu","fu","du","tu","nu","lu","gu","ku","hu","zu","cu","su","zhu","chu","shu","ru",
        "wa","gua","kua","hua","zhua","chua","shua","rua",
        "wo","duo","tuo","nuo","luo","guo","kuo","huo","zuo","cuo","suo","zhuo","chuo","shuo","ruo",
        "wai","guai","kuai","huai","zhuai","chuai","shuai",
        "wei","dui","tui","gui","kui","hui","zui","cui","sui","zhui","chui","shui","rui",
        "wan","duan","tuan","nuan","luan","guan","kuan","huan","zuan","cuan","suan","zhuan","chuan","shuan","ruan",
        "wang","guang","kuang","huang","zhuang","chuang","shuang",
        "wen","dun","tun","nun","lun","gun","kun","hun","zun","cun","sun","zhun","chun","shun","run",
        "weng",

        // i 系（含 y-）
        "yi","bi","pi","mi","di","ti","ni","li","zi","ci","si","zhi","chi","shi","ri","ji","qi","xi",
        "ya","dia","lia","jia","qia","xia",
        "ye","bie","pie","mie","die","tie","nie","lie","jie","qie","xie",
        "yao","biao","piao","miao","diao","tiao","niao","liao","jiao","qiao","xiao",
        "you","miu","diu","niu","liu","jiu","qiu","xiu",
        "yan","bian","pian","mian","dian","tian","nian","lian","jian","qian","xian",
        "yang","niang","liang","jiang","qiang","xiang",
        "yin","bin","pin","min","nin","lin","jin","qin","xin",
        "ying","bing","ping","ming","ding","ting","ning","ling","jing","qing",
        "yong","jiong","qiong","xiong",

        // ü 系（用 v 表示 n/l 的 ü；j/q/x/y 仍用 u 记法：ju/qu/xu/yu 等）
        "yu","nv","lv","ju","qu","xu",
        "yue","nve","lve","jue","que","xue",
        "yuan","juan","quan","xuan",
        "yun","jun","qun","xun",

        // er（该组合表通常单列，这里确保包含）
        "er"
    )
}
