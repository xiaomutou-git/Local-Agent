package com.localagent.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档内容危险检测（移植 docSafety.js）。
 *
 * 用途：文档读取拦截、知识库建索引过滤、记忆入库过滤。
 * 对抗跨标签拆分：同时检测原文与「去空白/零宽字符」折叠文本。
 */
public final class DocSafety {
    private DocSafety() {}

    private record Rule(Pattern re, String label) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("p\\s*o\\s*w\\s*e\\s*r\\s*s\\s*h\\s*e\\s*l\\s*l|p\\s*w\\s*s\\s*h", Pattern.CASE_INSENSITIVE), "PowerShell 命令"),
            new Rule(Pattern.compile("c\\s*m\\s*d(?:\\s*\\.?\\s*e\\s*x\\s*e)?\\s*[/\\-]\\s*c", Pattern.CASE_INSENSITIVE), "cmd 命令执行"),
            new Rule(Pattern.compile("\\b(wmic|taskkill|shutdown|reg\\s+add|reg\\s+delete)\\b", Pattern.CASE_INSENSITIVE), "系统命令"),
            new Rule(Pattern.compile("\\b(format|del\\s+[a-z]:|rmdir\\s+/s|remove-item)\\b", Pattern.CASE_INSENSITIVE), "破坏性命令"),
            new Rule(Pattern.compile("c\\s*e\\s*r\\s*t\\s*u\\s*t\\s*i\\s*l|b\\s*i\\s*t\\s*s\\s*a\\s*d\\s*m\\s*i\\s*n|s\\s*c\\s*h\\s*t\\s*a\\s*s\\s*k\\s*s", Pattern.CASE_INSENSITIVE), "下载/计划任务滥用"),
            new Rule(Pattern.compile("(宏|macro|vba|vbe6)", Pattern.CASE_INSENSITIVE), "宏代码"),
            new Rule(Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE), "JavaScript 协议链接"),
            new Rule(Pattern.compile("vbscript\\s*:", Pattern.CASE_INSENSITIVE), "VBScript 协议链接"),
            new Rule(Pattern.compile("data\\s*:\\s*text/html", Pattern.CASE_INSENSITIVE), "Data 协议 HTML"),
            new Rule(Pattern.compile("i\\s*g\\s*n\\s*o\\s*r\\s*e\\s+(?:a\\s*l\\s*l\\s+)?p\\s*r\\s*e\\s*v\\s*i\\s*o\\s*u\\s*s", Pattern.CASE_INSENSITIVE), "提示注入特征"));

    /** 返回命中的危险标签列表（空列表表示安全）。 */
    public static List<String> detect(String s) {
        List<String> issues = new ArrayList<>();
        if (s == null) return issues;
        String collapsed = s.replaceAll("[\\s\\u00A0\\u200B-\\u200D\\uFEFF]+", "");
        for (String text : collapsed.equals(s) ? List.of(s) : List.of(s, collapsed)) {
            for (Rule r : RULES) {
                if (r.re().matcher(text).find() && !issues.contains(r.label())) issues.add(r.label());
            }
        }
        return issues;
    }

    /** 存在任意危险特征。 */
    public static boolean hasDanger(String s) { return !detect(s).isEmpty(); }
}
