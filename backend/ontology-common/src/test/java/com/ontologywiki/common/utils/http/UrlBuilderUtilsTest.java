package com.ontologywiki.common.utils.http;

import cn.hutool.core.net.url.UrlBuilder;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * 验证 URL 构建器与文档示例保持一致。
 */
public class UrlBuilderUtilsTest {

    @Test
    public void shouldBuildDefaultAndCompleteUrl() {
        assertEquals("http://www.hutool.cn/",
                UrlBuilderUtils.create().setHost("www.hutool.cn").build());

        assertEquals("https://www.hutool.cn/aaa/bbb?ie=UTF-8&wd=test",
                UrlBuilderUtils.create()
                        .setScheme("https")
                        .setHost("www.hutool.cn")
                        .addPath("/aaa")
                        .addPath("bbb")
                        .addQuery("ie", "UTF-8")
                        .addQuery("wd", "test")
                        .build());
    }

    @Test
    public void shouldEncodeChineseAndRepeatedQueryParameters() {
        assertEquals("https://www.hutool.cn/s?ie=UTF-8&ie=GBK&wd=%E6%B5%8B%E8%AF%95",
                UrlBuilderUtils.create()
                        .setScheme("https")
                        .setHost("www.hutool.cn")
                        .addPath("/s")
                        .addQuery("ie", "UTF-8")
                        .addQuery("ie", "GBK")
                        .addQuery("wd", "测试")
                        .build());
    }

    @Test
    public void shouldDecodeMixedRawAndEncodedParameters() {
        UrlBuilder builder = UrlBuilderUtils.ofHttp(
                "www.hutool.cn/aaa/bbb/?a=张三&b=%e6%9d%8e%e5%9b%9b#frag1",
                StandardCharsets.UTF_8);

        assertEquals("张三", builder.getQuery().get("a"));
        assertEquals("李四", builder.getQuery().get("b"));
    }

    @Test
    public void shouldNormalizeHtmlEscapedQuerySeparator() {
        UrlBuilder builder = UrlBuilderUtils.ofHttp(
                "https://mp.weixin.qq.com/s?__biz=MzI5NjkyNTIxMg==&amp;mid=100000465&amp;idx=1",
                StandardCharsets.UTF_8);

        assertEquals("https://mp.weixin.qq.com/s?__biz=MzI5NjkyNTIxMg==&mid=100000465&idx=1",
                builder.build());
    }
}
