package dev.junxiex.mikuvideo.client.media;

/**
 * ffmpeg 标准错误输出的定长尾部缓存。
 *
 * <p>只保留最后若干行，避免长时间播放时错误输出无限增长占用内存。</p>
 *
 * @author JunXieX
 */
final class StderrRing {

    private final String[] lines;
    private int count;
    private int cursor;

    StderrRing(int capacity) {
        this.lines = new String[Math.max(1, capacity)];
    }

    /** 追加一行，空行忽略。 */
    synchronized void add(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        lines[cursor] = trimmed;
        cursor = (cursor + 1) % lines.length;
        if (count < lines.length) {
            count++;
        }
    }

    /** 返回最后 {@code n} 行，按时间先后用换行拼接；不足则返回现有的全部。 */
    synchronized String tail(int n) {
        int take = Math.min(Math.max(0, n), count);
        if (take == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(take * 48);
        int start = (cursor - take + lines.length) % lines.length;
        for (int i = 0; i < take; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines[(start + i) % lines.length]);
        }
        return builder.toString();
    }

    synchronized boolean isEmpty() {
        return count == 0;
    }
}
