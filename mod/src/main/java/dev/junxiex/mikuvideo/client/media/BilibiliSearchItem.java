package dev.junxiex.mikuvideo.client.media;

/** B 站搜索结果。 */
public record BilibiliSearchItem(String bvid, String title, String author, long durationMillis) {
    public String url() {
        return "https://www.bilibili.com/video/" + bvid;
    }
}
