package com.animetracker.util;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bangumi 英文标签 → 中文标签翻译。
 * 从 BangumiController 中抽离，方便维护。
 */
public class TagTranslationUtil {

    private static final Map<String, String> TAG_CN = new LinkedHashMap<>();
    static {
        TAG_CN.put("Action", "动作");
        TAG_CN.put("Adventure", "冒险");
        TAG_CN.put("Comedy", "喜剧");
        TAG_CN.put("Drama", "剧情");
        TAG_CN.put("Fantasy", "奇幻");
        TAG_CN.put("Horror", "恐怖");
        TAG_CN.put("Mystery", "悬疑");
        TAG_CN.put("Psychological", "心理");
        TAG_CN.put("Romance", "恋爱");
        TAG_CN.put("Sci-Fi", "科幻");
        TAG_CN.put("Slice of Life", "日常");
        TAG_CN.put("Sports", "运动");
        TAG_CN.put("Supernatural", "超自然");
        TAG_CN.put("Thriller", "惊悚");
        TAG_CN.put("Mecha", "机甲");
        TAG_CN.put("Music", "音乐");
        TAG_CN.put("Ecchi", "卖肉");
        TAG_CN.put("Mahou Shoujo", "魔法少女");
        TAG_CN.put("Historical", "历史");
        TAG_CN.put("Military", "军事");
        TAG_CN.put("Parody", "恶搞");
        TAG_CN.put("Police", "警察");
        TAG_CN.put("Samurai", "武士");
        TAG_CN.put("School", "校园");
        TAG_CN.put("Space", "太空");
        TAG_CN.put("Vampire", "吸血鬼");
        TAG_CN.put("Game", "游戏");
        TAG_CN.put("Harem", "后宫");
        TAG_CN.put("Isekai", "异世界");
        TAG_CN.put("Martial Arts", "武术");
        TAG_CN.put("Demons", "恶魔");
        TAG_CN.put("Magic", "魔法");
        TAG_CN.put("Seinen", "青年");
        TAG_CN.put("Shoujo", "少女");
        TAG_CN.put("Shounen", "少年");
        TAG_CN.put("Josei", "女性");
        TAG_CN.put("Gore", "血腥");
        TAG_CN.put("Reincarnation", "转生");
        TAG_CN.put("Reverse Harem", "逆后宫");
        TAG_CN.put("Super Power", "超能力");
        TAG_CN.put("Survival", "生存");
        TAG_CN.put("Idols", "偶像");
        TAG_CN.put("Anthropomorphic", "拟人");
        TAG_CN.put("Cars", "赛车");
        TAG_CN.put("CGDCT", "萌系");
        TAG_CN.put("Childcare", "育儿");
        TAG_CN.put("Combat Sports", "格斗");
        TAG_CN.put("Crossdressing", "伪娘");
        TAG_CN.put("Delinquents", "不良少年");
        TAG_CN.put("Detective", "侦探");
        TAG_CN.put("Educational", "教育");
        TAG_CN.put("Gag Humor", "搞笑");
        TAG_CN.put("Gender Bender", "性转");
        TAG_CN.put("Gourmet", "美食");
        TAG_CN.put("Love Polygon", "多角恋");
        TAG_CN.put("Medical", "医疗");
        TAG_CN.put("Mythology", "神话");
        TAG_CN.put("Organized Crime", "黑帮");
        TAG_CN.put("Otaku Culture", "御宅");
        TAG_CN.put("Pets", "宠物");
        TAG_CN.put("Philosophy", "哲学");
        TAG_CN.put("Racing", "竞速");
        TAG_CN.put("Showbiz", "演艺");
        TAG_CN.put("Strategy Game", "策略");
        TAG_CN.put("Team Sports", "团队运动");
        TAG_CN.put("Time Travel", "时间旅行");
        TAG_CN.put("Tokusatsu", "特摄");
        TAG_CN.put("Tragedy", "悲剧");
        TAG_CN.put("Visual Arts", "美术");
        TAG_CN.put("Workplace", "职场");
        TAG_CN.put("Yuri", "百合");
        TAG_CN.put("Yaoi", "耽美");
        TAG_CN.put("Avant Garde", "先锋");
        TAG_CN.put("Award Winning", "获奖");
        TAG_CN.put("Boys Love", "耽美");
        TAG_CN.put("Girls Love", "百合");
        TAG_CN.put("Romantic Subtext", "轻百合");
        TAG_CN.put("Suspense", "悬疑");
        TAG_CN.put("War", "战争");
        TAG_CN.put("Zombie", "丧尸");
        TAG_CN.put("Episodic", "单元剧");
    }

    /** 英文标签→中文，找不到返回原文 */
    public static String translate(String en) {
        return TAG_CN.getOrDefault(en, en);
    }

    /** 中文标签→英文（用于反向查找），找不到返回原文 */
    public static String reverseTranslate(String cn) {
        return TAG_CN.entrySet().stream()
                .filter(e -> e.getValue().equals(cn))
                .map(Map.Entry::getKey)
                .findFirst().orElse(cn);
    }

    /** 中文标签→所有可能的匹配集合（中文原文 + 对应的英文标签） */
    public static Set<String> reverseTranslateAll(String cn) {
        Set<String> tags = new HashSet<>();
        tags.add(cn); // 中文原文（数据库里很可能就是中文）
        TAG_CN.entrySet().stream()
                .filter(e -> e.getValue().equals(cn))
                .map(Map.Entry::getKey)
                .forEach(tags::add); // 对应的英文标签
        return tags;
    }

    /** 获取所有标签映射 */
    public static Map<String, String> getAll() {
        return Collections.unmodifiableMap(TAG_CN);
    }
}
