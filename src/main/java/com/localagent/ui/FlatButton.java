package com.localagent.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * 扁平按钮（纯自绘）。克制风格：
 * - PRIMARY：低饱和蓝实心（仅用于发送等主操作）
 * - GHOST：默认无边框灰字（顶栏次要操作），悬停才出现浅灰底
 * - DANGER：灰底红字（拒绝/停止），不描红边
 */
public final class FlatButton extends JButton {
    private static final long serialVersionUID = 1L;

    public enum Variant { PRIMARY, GHOST, DANGER }

    private final Variant variant;
    private boolean hover;
    private final int arc;

    public FlatButton(String text, Variant variant) {
        super(text);
        this.variant = variant;
        this.arc = UiTheme.RADIUS_SM;
        setFont(UiTheme.font(13, variant == Variant.PRIMARY ? Font.BOLD : Font.PLAIN));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
        });
    }

    public static FlatButton primary(String text) { return new FlatButton(text, Variant.PRIMARY); }
    public static FlatButton ghost(String text) { return new FlatButton(text, Variant.GHOST); }
    public static FlatButton danger(String text) { return new FlatButton(text, Variant.DANGER); }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth() - 1, h = getHeight() - 1;
        Color bg; Color fg;
        switch (variant) {
            case PRIMARY -> {
                if (!isEnabled()) { bg = new Color(0xA9BEEA); fg = Color.WHITE; }
                else { bg = hover ? UiTheme.PRIMARY_HOVER : UiTheme.PRIMARY; fg = UiTheme.ON_PRIMARY; }
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
            }
            case DANGER -> {
                bg = hover ? new Color(0xF3DFDD) : UiTheme.CHIP_BG;
                fg = UiTheme.DANGER;
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
            }
            default -> {
                bg = hover ? UiTheme.HOVER : new Color(0, 0, 0, 0);
                fg = UiTheme.TEXT_MUTED;
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
            }
        }
        g2.setColor(fg);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        String t = getText();
        int tx = (w - fm.stringWidth(t)) / 2;
        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(t, tx, ty);
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(Math.max(super.getPreferredSize().width, fm.stringWidth(getText()) + 30), 32);
    }
}
