package com.ontologywiki.common.utils.http;

import cn.hutool.core.net.url.UrlBuilder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * URL 构建与解析工具。
 *
 * <p>基于 Hutool {@link UrlBuilder} 提供统一入口，支持以下能力：</p>
 * <ul>
 *     <li>未指定协议时默认使用 HTTP 协议；</li>
 *     <li>分别设置协议、主机、端口、路径、查询参数和片段，并以链式方式构建 URL；</li>
 *     <li>构建 URL 时自动对中文路径、查询参数和片段进行编码，默认使用 UTF-8；</li>
 *     <li>解析同时包含原始中文和百分号编码的 URL，读取查询参数时统一返回解码后的值；</li>
 *     <li>兼容查询参数中使用 {@code &amp;amp;} 作为分隔符的 URL。</li>
 * </ul>
 *
 * <p>完整构建示例：</p>
 * <pre>{@code
 * String url = UrlBuilderUtils.create()
 *         .setScheme("https")
 *         .setHost("www.hutool.cn")
 *         .addPath("/aaa")
 *         .addPath("bbb")
 *         .addQuery("ie", "UTF-8")
 *         .addQuery("wd", "test")
 *         .build();
 * // https://www.hutool.cn/aaa/bbb?ie=UTF-8&wd=test
 * }</pre>
 *
 * <p>本类只负责提供构建器，具体解析和编码规则由 Hutool 统一实现，避免项目内重复维护 URL 规范。</p>
 *
 * @author ontologywiki
 */
public final class UrlBuilderUtils {

    /**
     * 工具类不允许实例化。
     */
    private UrlBuilderUtils() {
        throw new IllegalStateException("工具类不允许实例化");
    }

    /**
     * 创建一个空的 URL 构建器。
     *
     * <p>可通过 {@code setScheme}、{@code setHost}、{@code addPath} 和 {@code addQuery}
     * 等方法逐项设置 URL 内容。仅设置主机时，构建结果默认使用 HTTP 协议，例如：</p>
     * <pre>{@code
     * String url = UrlBuilderUtils.create().setHost("www.hutool.cn").build();
     * // http://www.hutool.cn/
     * }</pre>
     *
     * @return 可链式设置的 URL 构建器
     */
    public static UrlBuilder create() {
        return UrlBuilder.of();
    }

    /**
     * 使用 UTF-8 编码解析 HTTP 或 HTTPS URL。
     *
     * <p>输入可以包含未编码中文、已编码参数，或者二者混合。通过
     * {@code getQuery().get("参数名")} 获取参数时，返回解码后的原始值；再次调用
     * {@code build()} 时，会按照 UTF-8 统一编码。</p>
     *
     * @param url 待解析的 URL，允许省略协议
     * @return 解析后的 URL 构建器，可继续修改路径、参数或编码
     */
    public static UrlBuilder ofHttp(String url) {
        return UrlBuilder.ofHttp(url, StandardCharsets.UTF_8);
    }

    /**
     * 使用指定字符集解析 HTTP 或 HTTPS URL。
     *
     * <p>字符集同时用于解析时的解码和重新构建时的编码。若业务没有明确的编码要求，
     * 建议直接使用 {@link #ofHttp(String)}，保持系统默认的 UTF-8 规范。</p>
     *
     * @param url 待解析的 URL，允许省略协议
     * @param charset 解码和编码使用的字符集，例如 UTF-8 或 GBK
     * @return 解析后的 URL 构建器，可通过 {@code setCharset} 再次调整字符集
     */
    public static UrlBuilder ofHttp(String url, Charset charset) {
        return UrlBuilder.ofHttp(url, charset);
    }
}
