package com.localagent.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * 圆角面板：纯色填充 + 可选描边，自绘无立体效果。
 * 用于输入卡片、审批卡片、聊天气泡、状态胶囊等所有需要圆角矩形容器的场景。
 */
public class RoundedPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final int arc;
    private final Color fill;
    private final Color stroke;
    private final int strokeWidth;

    public RoundedPanel(int arc, Color fill, Color stroke, int strokeWidth) {
        this.arc = arc;
        this.fill = fill;
        this.stroke = stroke;
        this.strokeWidth = strokeWidth;
    }

    public RoundedPanel(LayoutManager layout, int arc, Color fill, Color stroke, int strokeWidth) {
        super(layout);
        this.arc = arc;
        this.fill = fill;
        this.stroke = stroke;
        this.strokeWidth = strokeWidth;
    }

    /**
     * 恒定透明：圆角区域外不绘制底板。
     * 以覆写查询方法替代在构造器中调用可覆写的 setOpaque(false)，
     * 避免子类构造期间发生 this 逃逸（-Xlint:this-escape），绘制期 Swing
     * 通过 isOpaque() 取得的结果与 setOpaque(false) 完全等价。
     * @return 恒为 false（自绘圆角，无需系统填充不透明底）
     */
    @Override
    public boolean isOpaque() { return false; }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (fill != null) {
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1f, getHeight() - 1f, arc, arc));
        }
        if (stroke != null && strokeWidth > 0) {
            g2.setColor(stroke);
            g2.setStroke(new BasicStroke(strokeWidth));
            g2.draw(new RoundRectangle2D.Float(strokeWidth / 2f, strokeWidth / 2f,
                    getWidth() - strokeWidth, getHeight() - strokeWidth, arc, arc));
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
