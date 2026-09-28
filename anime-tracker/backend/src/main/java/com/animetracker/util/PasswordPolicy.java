package com.animetracker.util;

/**
 * 密码强度策略.
 *
 * 单独抽出来是因为这条规则有三个使用方: 注册接口的参数校验、单元测试、
 * 以及前端表单的即时提示. 前两个可以直接引用这里的常量, 前端只能在 JS 里
 * 再写一遍 —— 所以这里必须有一份明确的、可被引用的定义, 而不是把正则散落在
 * 注解字符串里, 否则「改了后端忘了改前端」这种事迟早会发生.
 *
 * 强度定成「至少 8 位, 同时含字母和数字」, 是个刻意的克制选择:
 * 要求大小写+符号能提升一点强度, 但会让用户改成 Password1! 然后写在便签上,
 * 实际收益是负的. 真正有效的是长度和下界, 而不是符号种类.
 */
public final class PasswordPolicy {

    /** 最短长度. 与 {@code @Size} 里的取值必须一致, 所以那边直接引用这里的常量. */
    public static final int MIN_LENGTH = 8;

    /** 最长长度. 上限不是为了安全, 是防止超长输入把 BCrypt 变成拒绝服务手段. */
    public static final int MAX_LENGTH = 100;

    /**
     * 长度不合规时的提示语.
     *
     * 由上面两个常量拼出来, 而不是把「8-100」写死在字符串里 ——
     * 这样改长度时提示语会跟着变, 不会出现「提示说 8 位、实际校验 10 位」
     * 这种只会被用户发现的不一致. 常量之间拼接仍是编译期常量, 能直接用在注解上.
     */
    public static final String LENGTH_MESSAGE =
            "密码长度需在 " + MIN_LENGTH + "-" + MAX_LENGTH + " 个字符之间";

    /**
     * 必须同时包含字母和数字.
     *
     * 用两个前瞻断言而不是字符类: 关键差别在 {@code (?=.*[A-Za-z])} 只要求
     * 「某处有一个字母」, 不对位置和顺序作要求, 也就无法用「a1aaaaaa」之外
     * 的什么花招绕过. 长度上限由 {@code @Size} 单独负责, 不在这里再写一遍.
     *
     * 注意 {@code .} 不匹配换行 —— 密码里本来也不该有换行, 这里不需要放开.
     */
    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d).*$";

    public static final String MESSAGE = "密码必须同时包含字母和数字";

    private PasswordPolicy() {
    }
}
