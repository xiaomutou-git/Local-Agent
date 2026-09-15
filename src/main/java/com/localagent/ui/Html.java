package com.localagent.ui;

/**
 * HTML 转义（用于 JTextPane HTML 文档，防止文本内容破坏结构）。
 */
final class Html {
    private Html() {}
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder((int) (s.length() * 1.2));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                case '\n' -> sb.append("<br>");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
