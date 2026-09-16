package com.localagent.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * 会话列表单元格：白底，选中态为浅灰底 + 左侧 2px 蓝色指示条（克制，无彩色块）。
 */
public final class ConvCellRenderer extends JPanel implements ListCellRenderer<String> {
    private static final long serialVersionUID = 1L;

    private final JLabel title = new JLabel();

    public ConvCellRenderer() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(0, 12, 0, 10));
        title.setFont(UiTheme.font(13));
        add(title, BorderLayout.CENTER);
        setOpaque(false);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                  boolean selected, boolean focused) {
        // 会话标题由模型生成，必须 HTML 转义：以 "<html>" 开头的标题会被 JLabel
        // 按 HTML 渲染，造成标签注入/样式伪造（转义后不再以 <html> 字面量开头）
        title.setText(value == null ? "" : Html.esc(value));
        title.setForeground(selected ? UiTheme.TEXT : UiTheme.TEXT_MUTED);
        title.setFont(UiTheme.font(13, selected ? Font.BOLD : Font.PLAIN));
        putClientProperty("selected", selected);
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        boolean selected = Boolean.TRUE.equals(getClientProperty("selected"));
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (selected) {
            g2.setColor(UiTheme.CHIP_BG);
            g2.fill(new RoundRectangle2D.Float(4, 3, getWidth() - 8, getHeight() - 6, 6, 6));
            g2.setColor(UiTheme.PRIMARY);
            g2.fillRect(4, 10, 2, getHeight() - 20);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
