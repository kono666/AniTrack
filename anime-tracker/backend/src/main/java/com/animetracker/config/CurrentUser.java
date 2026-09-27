package com.animetracker.config;

import java.lang.annotation.*;

/**
 * 用在 Controller 方法参数上，自动注入当前登录用户。
 * 如果未登录则为 null（用于可选登录的接口）。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
