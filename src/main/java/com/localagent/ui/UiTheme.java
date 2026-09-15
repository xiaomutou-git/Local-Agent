package com.localagent.ui;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * 统一视觉规范（设计系统）——克制、中性、低饱和的桌面产品风格。
 *
 * 设计取向：以灰阶建立层次，强调色仅用于当前交互态（选中/主按钮），
 * 不使用高饱和色块、大圆角、渐变或装饰性符号；圆角统一 6/8，间距 4/8/12/16。
 * 参照成熟桌面客户端（Linear / ChatGPT 桌面端 / 飞书）的中性配色。
 */
public final class UiTheme {
    private UiTheme() {}

    // ---- 表面色（中性灰阶）----
    public static final Color WINDOW = new Color(0xFAFAFA);   // 窗口底（微灰）
    public static final Color SIDEBAR = new Color(0xF6F6F7);  // 侧栏比主区略深
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(0xE5E6E8);    // 常规分隔
    public static final Color BORDER_STRONG = new Color(0xD4D6D9);
    public static final Color CHIP_BG = new Color(0xF2F3F5);   // 工具条目/幽灵底
    public static final Color HOVER = new Color(0xEDEEEF);     // 列表/按钮悬停

    // ---- 文字 ----
    public static final Color TEXT = new Color(0x1F2329);
    public static final Color TEXT_MUTED = new Color(0x646A73);
    public static final Color TEXT_FAINT = new Color(0x8F959E);
    public static final Color ON_PRIMARY = Color.WHITE;

    // ---- 强调色（低饱和蓝，仅交互态）----
    public static final Color PRIMARY = new Color(0x3370FF);
    public static final Color PRIMARY_HOVER = new Color(0x2860E0);
    public static final Color PRIMARY_PRESS = new Color(0x1F4FC2);
    public static final Color PRIMARY_SOFT = new Color(0xEEF2FF);   // 选中浅底

    // ---- 语义色（降饱和）----
    public static final Color DANGER = new Color(0xD54941);
    public static final Color DANGER_HOVER = new Color(0xC13830);
    public static final Color DANGER_SOFT = new Color(0xFBECEC);
    public static final Color WARNING = new Color(0xB8862B);
    public static final Color WARNING_SOFT = new Color(0xFBF3E3);
    public static final Color SUCCESS = new Color(0x2E9E63);
    public static final Color SUCCESS_SOFT = new Color(0xEBF7F0);

    // ---- 尺度（小圆角，更像桌面软件）----
    public static final int RADIUS = 8;
    public static final int RADIUS_SM = 6;
    public static final int SP = 8;

    private static final String FONT_FAMILY = "Microsoft YaHei UI";

    public static Font font(int size, int style) { return new Font(FONT_FAMILY, style, size); }
    public static Font font(int size) { return font(size, Font.PLAIN); }
    public static Font titleFont() { return font(16, Font.BOLD); }
    public static Font bodyFont() { return font(14); }
    public static Font smallFont() { return font(12); }

    public static void applyGlobal() {
        UIManager.put("ToolTip.background", CARD);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.border", new RoundedBorder(BORDER, 1, 6));
        UIManager.put("PopupMenu.border", new RoundedBorder(BORDER, 1, 8));
    }

    /** 描边圆角边框（支持自定义描边粗细与圆角）。 */
    public static final class RoundedBorder extends AbstractBorder {
        private static final long serialVersionUID = 1L;

        private final Color color;
        private final int thickness;
        private final int arc;
        public RoundedBorder(Color color, int thickness, int arc) {
            this.color = color; this.thickness = thickness; this.arc = arc;
        }
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(thickness));
            g2.draw(new RoundRectangle2D.Float(x + thickness / 2f, y + thickness / 2f,
                    w - thickness, h - thickness, arc, arc));
            g2.dispose();
        }
        @Override
        public Insets getBorderInsets(Component c) { return new Insets(thickness + 6, thickness + 10, thickness + 6, thickness + 10); }
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(thickness + 6, thickness + 10, thickness + 6, thickness + 10); return insets;
        }
    }

    /** 把滚动条改成细窄圆角样式（无凸出轨道）。 */
    public static void thinScrollBar(JScrollPane scroll) {
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
        scroll.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                this.trackColor = new Color(0, 0, 0, 0);
                this.thumbColor = new Color(0xCFD6DE);
            }
            @Override protected JButton createDecreaseButton(int o) { return zeroButton(); }
            @Override protected JButton createIncreaseButton(int o) { return zeroButton(); }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { /* 透明轨道 */ }
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isThumbRollover() ? new Color(0xAEB7C2) : new Color(0xCFD6DE));
                g2.fill(new RoundRectangle2D.Float(r.x + 1, r.y + 1, r.width - 2, r.height - 2, 6, 6));
                g2.dispose();
            }
        });
        if (scroll.getHorizontalScrollBar() != null) scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 9));
    }

    private static JButton zeroButton() {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(0, 0));
        b.setMinimumSize(new Dimension(0, 0));
        b.setMaximumSize(new Dimension(0, 0));
        return b;
    }
}
