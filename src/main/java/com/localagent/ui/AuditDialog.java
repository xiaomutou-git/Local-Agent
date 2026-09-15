package com.localagent.ui;

import com.localagent.db.Audit;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * 安全审计日志查看对话框（主题化，纯文本无 HTML 注入面）。
 */
final class AuditDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JTextArea area = new JTextArea();

    AuditDialog(JFrame owner) {
        super(owner, "安全操作日志", true);
        setSize(780, 580);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(UiTheme.WINDOW);

        area.setEditable(false);
        area.setFont(new Font("Consolas", Font.PLAIN, 12));
        area.setBorder(new EmptyBorder(12, 14, 12, 14));
        area.setBackground(UiTheme.CARD);
        area.setForeground(UiTheme.TEXT);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(null);
        UiTheme.thinScrollBar(scroll);
        add(scroll, BorderLayout.CENTER);

        FlatButton refresh = FlatButton.ghost("刷新");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        top.setBackground(UiTheme.WINDOW);
        top.add(refresh);
        add(top, BorderLayout.NORTH);
        refresh.addActionListener(e -> area.setText(render()));
        area.setText(render());
    }

    private String render() {
        List<Map<String, Object>> list = Audit.recent(300);
        StringBuilder sb = new StringBuilder();
        for (var a : list) {
            String t = String.valueOf(a.get("t"));
            String dot = t.length() >= 19 ? t.substring(0, 19).replace("T", " ") : t;
            sb.append(dot).append(' ').append(pad(a.get("event"), 9));
            if (a.get("tool") != null) sb.append(' ').append(pad(a.get("tool"), 16));
            if (a.get("risk") != null) sb.append(' ').append(a.get("risk"));
            if (a.get("reason") != null) sb.append("  → ").append(a.get("reason"));
            if (a.get("error") != null) sb.append("  err=").append(a.get("error"));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String pad(Object o, int n) {
        String s = String.valueOf(o);
        return s.length() >= n ? s.substring(0, n) : s + " ".repeat(n - s.length());
    }
}
