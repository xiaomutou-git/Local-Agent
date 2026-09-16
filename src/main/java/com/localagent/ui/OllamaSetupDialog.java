package com.localagent.ui;

import com.localagent.config.Config;
import com.localagent.ollama.OllamaEnv;
import com.localagent.ollama.OllamaSetup;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URI;
import java.util.List;

/**
 * 首次启动引导对话框：检测 Ollama 安装/服务/模型状态，经用户一键授权后完成
 * "下载安装器（含数字签名校验）→ 启动服务 → 按电脑内存拉取合适模型"的闭环。
 *
 * 设计要点：
 * - 模态对话框，完成/跳过后才进入主界面；所有耗时操作在后台线程执行，
 *   Listener 回调统一切回 EDT 更新控件；
 * - 模型档位按物理内存预选，用户可改选；视觉版（qwen2.5vl）仅在该档位存在时可选；
 * - 跳过后写 ollamaBootstrapDone 配置，不再打扰（可日后在设置中自行配置）；
 * - 任何失败都给出"重试 / 手动下载 / 跳过"出口，错误信息不吞掉。
 *
 * 创建时间：2026-09-16。核心用途：零经验用户首次双击 exe 的自动环境配置。
 */
public final class OllamaSetupDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    /** 手动下载页面（仅在用户点击按钮时打开）。 */
    private static final String DOWNLOAD_PAGE = "https://ollama.com/download/windows";

    /** 档位单选按钮（与 OPTIONS 同序）。 */
    private final JRadioButton[] optionRadios;

    /**
     * 与单选按钮一一对应的档位规格。
     * transient：List 接口不承诺可序列化（javac [serial] 警告），对话框本身
     * 也从不参与序列化，显式标记语义最准确。
     */
    private final transient List<OllamaEnv.ModelOption> optionList = OllamaEnv.options();

    /** 推荐档位的按钮组索引（初始选中项）。 */
    private final int recommendedIndex;

    /** 使用同档视觉模型勾选框。 */
    private final JCheckBox visionBox;

    /** 不确定/确定进度条。 */
    private final JProgressBar progressBar;

    /** 步骤文本。 */
    private final JLabel stageLabel;

    /** 进度细节文本。 */
    private final JLabel detailLabel;

    /** 错误区（失败时展示，支持手动出口提示）。 */
    private final JTextArea errorArea;

    /** 主操作按钮（开始/重试）。 */
    private final JButton primaryButton;

    /** 跳过按钮。 */
    private final JButton skipButton;

    /** 工作进行中的取消按钮。 */
    private final JButton cancelButton;

    /** 手动打开下载页按钮。 */
    private final JButton manualButton;

    /** 后台线程取消标记（volatile 保证工作线程可见）。 */
    private volatile boolean cancelled;

    /** 后台工作线程引用（用于取消期间禁止重复启动）。 */
    private transient Thread worker;

    /**
     * 按需弹出引导：已就绪（服务在线且本地有模型）时直接写完成标记不弹窗。
     * @param owner 父窗口（启动阶段主窗口尚未创建，允许 null）
     */
    public static void showIfNeeded(Window owner) {
        String baseUrl = Config.getString("baseUrl", "http://127.0.0.1:11434");
        OllamaSetup.Stage detected;
        try {
            detected = OllamaSetup.detect(baseUrl);
        } catch (Exception e) {
            detected = OllamaSetup.Stage.NEED_MODEL; // 探测异常按需要引导处理，不阻断启动
        }
        if (detected == OllamaSetup.Stage.READY) {
            OllamaSetup.markDone();
            return;
        }
        OllamaSetupDialog dlg = new OllamaSetupDialog(owner, detected, baseUrl);
        // 窗口真正打开后再请求置前：配合 alwaysOnTop 突破前台锁，确保不被其他窗口淹没
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) { dlg.toFront(); }
        });
        dlg.setVisible(true);
    }

    /**
     * 创建对话框并按阶段初始化控件状态（不立即显示）。
     * @param owner 父窗口，允许 null
     * @param stage 已探测出的引导阶段
     * @param baseUrl 配置中的 Ollama 回环地址
     */
    public OllamaSetupDialog(Window owner, OllamaSetup.Stage stage, String baseUrl) {
        super(owner, "首次使用配置", ModalityType.APPLICATION_MODAL);

        long ram = OllamaEnv.totalPhysicalMemory();
        OllamaEnv.ModelOption recommended = OllamaEnv.recommend(ram);
        int recIdx = Math.max(0, optionList.indexOf(recommended));
        this.recommendedIndex = recIdx;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(560, 480);
        setResizable(false);
        setLocationRelativeTo(owner);
        // 首次启动时主窗口尚未创建，Windows 前台锁会阻止后台进程抢占焦点，
        // 普通对话框可能被浏览器/IDE 等全屏窗口完全挡住，用户误以为"双击没反应"。
        // 引导期间置顶（模态、生命周期短，关闭即释放）确保第一时间可见。
        setAlwaysOnTop(true);
        getContentPane().setBackground(UiTheme.WINDOW);
        setLayout(new BorderLayout());

        // ---- 顶部说明 ----
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(new EmptyBorder(18, 20, 8, 20));
        JLabel title = new JLabel("本机还没有可用的本地模型");
        title.setFont(UiTheme.titleFont());
        title.setAlignmentX(LEFT_ALIGNMENT);
        stageLabel = new JLabel(stageIntro(stage));
        stageLabel.setFont(UiTheme.font(12));
        stageLabel.setForeground(UiTheme.TEXT_MUTED);
        stageLabel.setAlignmentX(LEFT_ALIGNMENT);
        stageLabel.setBorder(new EmptyBorder(8, 0, 0, 0));
        JLabel hw = new JLabel("本机物理内存：" + Math.round(ram / (1024.0 * 1024 * 1024))
                + " GB（已按内存预选最合适的模型档位，可自行调整）");
        hw.setFont(UiTheme.font(11));
        hw.setForeground(UiTheme.TEXT_FAINT);
        hw.setAlignmentX(LEFT_ALIGNMENT);
        hw.setBorder(new EmptyBorder(6, 0, 0, 0));
        top.add(title);
        top.add(stageLabel);
        top.add(hw);
        add(top, BorderLayout.NORTH);

        // ---- 中部：模型档位 + 视觉选项 + 进度 ----
        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(new EmptyBorder(4, 20, 4, 20));

        JPanel radioPanel = new JPanel();
        radioPanel.setOpaque(true);
        radioPanel.setBackground(UiTheme.CARD);
        radioPanel.setBorder(BorderFactory.createLineBorder(UiTheme.BORDER));
        radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.Y_AXIS));
        ButtonGroup group = new ButtonGroup();
        optionRadios = new JRadioButton[optionList.size()];
        for (int i = 0; i < optionList.size(); i++) {
            OllamaEnv.ModelOption o = optionList.get(i);
            String rec = i == recIdx ? "（推荐）" : "";
            JRadioButton rb = new JRadioButton(
                    o.tag() + "　" + o.sizeText() + rec + " — " + o.label());
            rb.setFont(UiTheme.font(12));
            rb.setBackground(UiTheme.CARD);
            rb.setSelected(i == recIdx);
            rb.setActionCommand(String.valueOf(i));
            rb.addActionListener(e -> syncVisionState());
            rb.setAlignmentX(LEFT_ALIGNMENT);
            rb.setBorder(new EmptyBorder(6, 10, 6, 10));
            group.add(rb);
            radioPanel.add(rb);
            optionRadios[i] = rb;
        }
        radioPanel.setAlignmentX(LEFT_ALIGNMENT);
        radioPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, radioPanel.getPreferredSize().height));
        center.add(radioPanel);

        visionBox = new JCheckBox("改用同档视觉版 qwen2.5vl（可分析图片，体积略大；低内存档位无此选项）");
        visionBox.setFont(UiTheme.font(11));
        visionBox.setOpaque(false);
        visionBox.setAlignmentX(LEFT_ALIGNMENT);
        visionBox.setBorder(new EmptyBorder(8, 2, 2, 0));
        center.add(visionBox);
        syncVisionState();

        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setAlignmentX(LEFT_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        progressBar.setBorder(new EmptyBorder(10, 0, 4, 0));
        progressBar.setVisible(false);
        center.add(progressBar);

        detailLabel = new JLabel(" ");
        detailLabel.setFont(UiTheme.font(11));
        detailLabel.setForeground(UiTheme.TEXT_MUTED);
        detailLabel.setAlignmentX(LEFT_ALIGNMENT);
        center.add(detailLabel);

        errorArea = new JTextArea(4, 40);
        errorArea.setFont(UiTheme.font(11));
        errorArea.setForeground(UiTheme.DANGER);
        errorArea.setBackground(UiTheme.WINDOW);
        errorArea.setLineWrap(true);
        errorArea.setWrapStyleWord(true);
        errorArea.setEditable(false);
        errorArea.setAlignmentX(LEFT_ALIGNMENT);
        errorArea.setVisible(false);
        center.add(errorArea);

        add(center, BorderLayout.CENTER);

        // ---- 底部按钮 ----
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(new EmptyBorder(8, 20, 16, 20));

        manualButton = new JButton("手动下载/说明");
        manualButton.setFont(UiTheme.font(11));
        manualButton.addActionListener(e -> openDownloadPage());
        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftBtns.setOpaque(false);
        leftBtns.add(manualButton);
        bottom.add(leftBtns, BorderLayout.WEST);

        primaryButton = new JButton(primaryText(stage));
        primaryButton.setFont(UiTheme.font(12, Font.BOLD));
        primaryButton.addActionListener(e -> startWork());
        skipButton = new JButton("跳过，稍后自己配置");
        skipButton.setFont(UiTheme.font(11));
        skipButton.addActionListener(e -> skip());
        cancelButton = new JButton("取消下载");
        cancelButton.setFont(UiTheme.font(11));
        cancelButton.setVisible(false);
        cancelButton.addActionListener(e -> requestCancel());
        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightBtns.setOpaque(false);
        rightBtns.add(cancelButton);
        rightBtns.add(skipButton);
        rightBtns.add(primaryButton);
        bottom.add(rightBtns, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
    }

    /**
     * 按阶段生成首行说明。
     * @param st 引导阶段
     * @return 中文说明文本
     */
    private static String stageIntro(OllamaSetup.Stage st) {
        return switch (st) {
            case NEED_INSTALL -> "未检测到 Ollama。将从 ollama.com 官方下载安装器（经数字签名校验后运行），并下载你选择的模型。";
            case NEED_SERVICE -> "已安装 Ollama，但后台服务未运行。将自动启动服务并下载你选择的模型。";
            case NEED_MODEL -> "Ollama 服务正常，但本地还没有模型。选择一个适合本机内存的模型即可开始对话。";
            case READY -> "Ollama 与模型均已就绪。";
        };
    }

    /**
     * 按阶段生成主按钮文本。
     * @param st 引导阶段
     * @return 按钮文本
     */
    private static String primaryText(OllamaSetup.Stage st) {
        return switch (st) {
            case NEED_INSTALL -> "一键安装 Ollama 与模型";
            case NEED_SERVICE -> "启动服务并下载模型";
            case NEED_MODEL -> "下载所选模型";
            case READY -> "完成";
        };
    }

    /**
     * 根据当前选中档位，启用/禁用并清理视觉版勾选框。
     */
    private void syncVisionState() {
        OllamaEnv.ModelOption cur = selectedOption();
        boolean canVision = cur.visionTag() != null;
        visionBox.setEnabled(canVision);
        if (!canVision) visionBox.setSelected(false);
    }

    /**
     * 读取当前单选按钮对应的档位。
     * @return 选中的 ModelOption（无选中时回退推荐档）
     */
    private OllamaEnv.ModelOption selectedOption() {
        for (JRadioButton rb : optionRadios) if (rb.isSelected()) {
            try { return optionList.get(Integer.parseInt(rb.getActionCommand())); }
            catch (NumberFormatException ignored) { /* 落到推荐 */ }
        }
        return optionList.get(recommendedIndex);
    }

    /**
     * 读取最终要拉取的模型标签（视觉勾选时取 visionTag）。
     * @return 模型标签字符串
     */
    private String selectedTag() {
        OllamaEnv.ModelOption o = selectedOption();
        return visionBox.isSelected() && o.visionTag() != null ? o.visionTag() : o.tag();
    }

    /**
     * 启动后台工作线程执行安装/启动/拉取全流程；期间锁定输入控件。
     */
    private void startWork() {
        if (worker != null && worker.isAlive()) return;
        errorArea.setVisible(false);
        cancelled = false;
        setInputsEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        cancelButton.setVisible(true);
        primaryButton.setVisible(false);

        String tag = selectedTag();
        String baseUrl = Config.getString("baseUrl", "http://127.0.0.1:11434");
        worker = new Thread(() -> runWork(tag, baseUrl), "ollama-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 工作线程主体：调用编排层并把回调切回 EDT；成功写标记并关闭，失败展示错误。
     * @param tag 目标模型标签
     * @param baseUrl 服务地址
     */
    private void runWork(String tag, String baseUrl) {
        OllamaSetup.Listener listener = new OllamaSetup.Listener() {
            @Override public void stage(String text) {
                SwingUtilities.invokeLater(() -> stageLabel.setText(text));
            }
            @Override public void progress(double ratio, String detail) {
                SwingUtilities.invokeLater(() -> {
                    if (ratio < 0) {
                        progressBar.setIndeterminate(true);
                    } else {
                        progressBar.setIndeterminate(false);
                        progressBar.setValue((int) Math.round(ratio * 100));
                    }
                    detailLabel.setText(detail == null ? " " : detail);
                });
            }
            @Override public boolean cancelled() { return cancelled; }
        };
        try {
            OllamaSetup.ensureReady(tag, baseUrl, listener);
            SwingUtilities.invokeLater(() -> {
                OllamaSetup.markDone();
                JOptionPane.showMessageDialog(this, "已完成：Ollama 服务与模型 " + tag + " 均已就绪。",
                        "配置完成", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            });
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> showError(ex));
        }
    }

    /**
     * 切换为失败态：展示错误、恢复重试与手动出口。
     * @param ex 工作线程捕获的异常
     */
    private void showError(Exception ex) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        detailLabel.setText(" ");
        String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        errorArea.setText("未能自动完成：" + msg
                + "\n可点击「重试」，或用「手动下载/说明」自行安装后重启本程序；安装过程中可随时跳过。");
        errorArea.setVisible(true);
        setInputsEnabled(true);
        cancelButton.setVisible(false);
        primaryButton.setVisible(true);
        primaryButton.setText("重试");
        worker = null;
    }

    /**
     * 切换输入控件可用性（工作期间锁定档位选择与跳过）。
     * @param enabled true=可交互
     */
    private void setInputsEnabled(boolean enabled) {
        for (JRadioButton rb : optionRadios) rb.setEnabled(enabled);
        visionBox.setEnabled(enabled && selectedOption().visionTag() != null);
        skipButton.setEnabled(enabled);
        manualButton.setEnabled(enabled);
        primaryButton.setEnabled(enabled);
    }

    /**
     * 请求取消后台下载/等待（工作线程在下一检查点退出）。
     */
    private void requestCancel() {
        cancelled = true;
        cancelButton.setEnabled(false);
        detailLabel.setText("正在取消…");
    }

    /**
     * 跳过引导：写完成标记并关闭主界面继续启动（服务状态由主界面状态栏呈现）。
     */
    private void skip() {
        OllamaSetup.markDone();
        dispose();
    }

    /**
     * 用系统默认浏览器打开官方下载页（DOM/浏览器操作包裹异常，避免页面崩溃）。
     */
    private void openDownloadPage() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(DOWNLOAD_PAGE));
            } else {
                JOptionPane.showMessageDialog(this, "请手动在浏览器打开：" + DOWNLOAD_PAGE,
                        "手动下载", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "无法打开浏览器，请手动访问：" + DOWNLOAD_PAGE,
                    "手动下载", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
