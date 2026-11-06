package dev.junxiex.mikuvideo.client.media.resolve;

import java.nio.file.Path;

/** 把页面地址解析成可直接解码的媒体地址。实现必须是线程安全的。 */
public interface MediaResolver {
    ResolvedMedia resolve(String url, Path cookiesFile, int maxHeight, String proxy) throws Exception;
}
