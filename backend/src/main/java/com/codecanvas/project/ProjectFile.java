package com.codecanvas.project;

/**
 * 项目里的一个文件。
 *
 * @param path    文件路径，例如 index.html
 * @param content 文件全文
 */
public record ProjectFile(String path, String content) {

    public int bytes() {
        return content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
