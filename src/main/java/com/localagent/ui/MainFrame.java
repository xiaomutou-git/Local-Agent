package com.localagent.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localagent.agent.Agent;
import com.localagent.agent.ToolCard;
import com.localagent.agent.UiCallback;
import com.localagent.config.Config;
import com.localagent.knowledge.Knowledge;
import com.localagent.memory.MemoryStore;
import com.localagent.ollama.OllamaClient;
import com.localagent.scheduler.ReminderScheduler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.util.*;

/**
 * 主窗口（Swing 现代浅色风）。
 *
 * 骨架（组件/布局层，非单纯换肤）：
 * - 左侧 260 白色侧栏：品牌区 + 主色「+ 新对话」+ 自绘会话列表
 * - 顶栏：连接状态胶囊 + 模型选择 + 次要操作（刷新/设置/安全日志）
 * - 中部：气泡式聊天区（ChatPane），空状态欢迎引导
 * - 底部：动态审批卡片 + 圆角输入卡片（回车发送，Shift+回车换行）
 */
public final class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;

    // 以下协作者与运行期状态均不参与 Java 序列化（Swing 窗口从不做序列化），
    // 标 transient 既通过 [serial] 检查，也准确表达"仅运行期持有"的意图
    private final transient Agent agent;
    private final transient OllamaClient ollama;
    // memory/scheduler 由 Main 装配后通过 Agent 与回调使用，窗口本身不直接持有
    private final transient Knowledge knowledge;
    // MCP 本地服务管理器：转交给设置对话框做状态展示/测试连接/热重连
    private final transient com.localagent.mcp.McpManager mcpManager;

    private final ChatPane chatPane = new ChatPane();
    private final JComboBox<String> modelBox = new JComboBox<>();
    private final StatusPill statusPill = new StatusPill();
    private final DefaultListModel<String> convModel = new DefaultListModel<>();
    private final JList<String> convList = new JList<>(convModel);
    private final JTextArea input = new JTextArea(3, 40);
    private final FlatButton sendBtn = FlatButton.primary("发送");
    private final FlatButton stopBtn = FlatButton.danger("停止");
    private final JPanel approvalPanel = new JPanel();
    private final transient Object approvalLock = new Object();

    private String activeConvId;
    /** 与 convModel 逐行对齐的会话 ID 列表，避免靠标题反查（重名会错位）。 */
    private final transient java.util.List<String> convIds = new ArrayList<>();
    private final transient Map<String, String> convIdToTitle = new LinkedHashMap<>();
    private transient TrayIcon trayIcon;

    public MainFrame(Agent agent, OllamaClient ollama, MemoryStore memory,
                     ReminderScheduler scheduler, Knowledge knowledge,
                     com.localagent.mcp.McpManager mcpManager) {
        super("本机助手");
        this.agent = agent; this.ollama = ollama; this.knowledge = knowledge;
        this.mcpManager = mcpManager;
        setSize(1200, 820);
        setMinimumSize(new Dimension(960, 640));
        // P0-4：关窗不直接退出（否则最小化到托盘后提醒调度会随之终止），
        // 统一交给 windowClosing 处理器按配置决定隐藏或退出
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(UiTheme.WINDOW);
        buildUi();
        setupTray();
        setupWindowClosing();
        new javax.swing.Timer(3000, e -> refreshStatus(false)).start();
    }

    // ================= 布局 =================
    private void buildUi() {
        getContentPane().add(buildSidebar(), BorderLayout.WEST);
        getContentPane().add(buildCenter(), BorderLayout.CENTER);
        bindEvents();
    }

    private JComponent buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 0));
        sidebar.setBackground(UiTheme.SIDEBAR);
        sidebar.setBorder(new MatteBorder(0, 0, 0, 1, UiTheme.BORDER));
        sidebar.setPreferredSize(new Dimension(248, 0));

        // 品牌区（纯文字，无彩色方块）
        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBorder(new EmptyBorder(16, 16, 10, 16));
        JLabel bName = new JLabel("本机助手");
        bName.setFont(UiTheme.font(14, Font.BOLD));
        bName.setForeground(UiTheme.TEXT);
        JLabel bSub = new JLabel("Local Ollama Agent");
        bSub.setFont(UiTheme.font(11));
        bSub.setForeground(UiTheme.TEXT_FAINT);
        bSub.setAlignmentX(Component.LEFT_ALIGNMENT);
        bName.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.add(bName); brand.add(Box.createVerticalStrut(2)); brand.add(bSub);

        // 新对话：轻量描边按钮（非整块主色）
        JPanel newWrap = new JPanel(new BorderLayout());
        newWrap.setOpaque(false);
        newWrap.setBorder(new EmptyBorder(0, 16, 10, 16));
        JButton newBtn = FlatButton.ghost("＋ 新对话");
        newBtn.setHorizontalAlignment(SwingConstants.LEFT);
        newBtn.setName("new-conversation");
        newBtn.setPreferredSize(new Dimension(100, 32));
        newWrap.add(newBtn, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(brand, BorderLayout.NORTH);
        north.add(newWrap, BorderLayout.SOUTH);
        sidebar.add(north, BorderLayout.NORTH);

        // 会话列表
        convList.setCellRenderer(new ConvCellRenderer());
        convList.setFixedCellHeight(38);
        convList.setOpaque(false);
        convList.setBackground(UiTheme.SIDEBAR);
        convList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        convList.setBorder(new EmptyBorder(2, 8, 8, 8));
        JScrollPane listScroll = new JScrollPane(convList);
        listScroll.getViewport().setOpaque(false);
        listScroll.getViewport().setBackground(UiTheme.SIDEBAR);
        listScroll.setOpaque(false);
        UiTheme.thinScrollBar(listScroll);
        sidebar.add(listScroll, BorderLayout.CENTER);

        return sidebar;
    }

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout(0, 0));
        center.setBackground(UiTheme.WINDOW);
        center.add(buildTopBar(), BorderLayout.NORTH);

        // 聊天滚动区
        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.getViewport().setBackground(UiTheme.WINDOW);
        chatScroll.setOpaque(false);
        chatScroll.getViewport().setOpaque(false);
        UiTheme.thinScrollBar(chatScroll);
        center.add(chatScroll, BorderLayout.CENTER);

        center.add(buildBottomDock(), BorderLayout.SOUTH);
        return center;
    }

    private JComponent buildTopBar() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(UiTheme.CARD);
        top.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, UiTheme.BORDER), new EmptyBorder(10, 16, 10, 16)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(statusPill);
        top.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JLabel modelLabel = new JLabel("模型");
        modelLabel.setFont(UiTheme.font(13));
        modelLabel.setForeground(UiTheme.TEXT_MUTED);
        styleModelBox();
        right.add(modelLabel); right.add(modelBox);
        right.add(FlatButton.ghost("刷新"));
        right.add(FlatButton.ghost("设置"));
        right.add(FlatButton.ghost("安全日志"));
        top.add(right, BorderLayout.EAST);

        // 用 name 标记以便挂事件（避免局部变量被重建）
        right.getComponent(2).setName("btn-refresh");
        right.getComponent(3).setName("btn-settings");
        right.getComponent(4).setName("btn-audit");
        return top;
    }

    private void styleModelBox() {
        modelBox.setPreferredSize(new Dimension(220, 32));
        modelBox.setFont(UiTheme.font(13));
        modelBox.setBackground(UiTheme.CARD);
        modelBox.setBorder(new UiTheme.RoundedBorder(UiTheme.BORDER_STRONG, 1, 7));
        DefaultListCellRenderer r = new DefaultListCellRenderer();
        modelBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel l = (JLabel) r.getListCellRendererComponent(list, value, index, selected, false);
            l.setBorder(new EmptyBorder(4, 10, 4, 10));
            l.setFont(UiTheme.font(13));
            l.setOpaque(true);
            l.setBackground(selected ? UiTheme.PRIMARY_SOFT : UiTheme.CARD);
            l.setForeground(UiTheme.TEXT);
            return l;
        });
    }

    private JComponent buildBottomDock() {
        JPanel dock = new JPanel(new BorderLayout(0, 8));
        dock.setOpaque(false);
        dock.setBorder(new EmptyBorder(0, 16, 14, 16));

        // 审批区（动态）
        approvalPanel.setLayout(new BoxLayout(approvalPanel, BoxLayout.Y_AXIS));
        approvalPanel.setOpaque(false);
        dock.add(approvalPanel, BorderLayout.NORTH);

        // 输入卡片（浅边框、低存在感）
        RoundedPanel inputCard = new RoundedPanel(new BorderLayout(0, 6), UiTheme.RADIUS,
                UiTheme.CARD, UiTheme.BORDER, 1);
        inputCard.setBorder(new EmptyBorder(9, 14, 8, 12));
        input.setLineWrap(true);
        input.setFont(UiTheme.font(14));
        input.setText("");
        input.setBackground(UiTheme.CARD);
        input.setBorder(null);
        inputCard.add(new JScrollPane(input) {{
            setBorder(null);
            setOpaque(false);
            getViewport().setOpaque(false);
            setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_AS_NEEDED);
            UiTheme.thinScrollBar(this);
        }}, BorderLayout.CENTER);

        JPanel inputBottom = new JPanel(new BorderLayout());
        inputBottom.setOpaque(false);
        JLabel hint = new JLabel("Enter 发送 · Shift+Enter 换行");
        hint.setFont(UiTheme.font(11));
        hint.setForeground(UiTheme.TEXT_FAINT);
        inputBottom.add(hint, BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        stopBtn.setVisible(false);
        btns.add(stopBtn); btns.add(sendBtn);
        inputBottom.add(btns, BorderLayout.EAST);
        inputCard.add(inputBottom, BorderLayout.SOUTH);
        dock.add(inputCard, BorderLayout.SOUTH);
        return dock;
    }

    // ================= 事件 =================
    private void bindEvents() {
        sendBtn.addActionListener(e -> doSend());
        stopBtn.addActionListener(e -> agent.stop());
        findByName(this, "new-conversation", JButton.class).addActionListener(e -> {
            activeConvId = agent.newConversation();
            chatPane.reset();
            refreshConversations();
        });
        findButton("btn-refresh").addActionListener(e -> refreshStatus(true));
        findButton("btn-settings").addActionListener(e ->
                new SettingsDialog(this, knowledge, mcpManager, agent.mcpCatalog()).setVisible(true));
        findButton("btn-audit").addActionListener(e -> new AuditDialog(this).setVisible(true));

        convList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int i = convList.getSelectedIndex();
            if (i < 0 || i >= convIds.size()) return;
            String id = convIds.get(i);
            if (id != null && !id.equals(activeConvId)) loadConv(id);
        });
        setupConversationMenu();

        // 回车发送，Shift+回车换行
        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send");
        input.getActionMap().put("send", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { doSend(); }
        });
        modelBox.addActionListener(e -> {
            if (Boolean.TRUE.equals(modelBox.getClientProperty("updating"))) return;
            Object sel = modelBox.getSelectedItem();
            if (sel != null && !sel.toString().isBlank()) {
                ObjectNode p = com.localagent.util.Json.mapper().createObjectNode();
                p.put("model", sel.toString());
                Config.set(p);
            }
        });
    }

    private JButton findButton(String name) {
        return findByName(this, name, JButton.class);
    }

    @SuppressWarnings("unchecked") // type.isInstance(c) 已保证转换安全
    private static <T extends Component> T findByName(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName()) && type.isInstance(c)) return (T) c;
            if (c instanceof Container child) {
                T hit = findByName(child, name, type);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /**
     * 创建系统托盘图标与菜单（P0-4 闭环）：
     * - 自绘 16×16 主色圆角底 + 白色「本」字图标，替代旧版全透明空图；
     * - 双击（Windows 下 ActionEvent 即双击）恢复主窗口；
     * - 右键菜单：显示主窗口 / 退出（真正结束进程）。
     * 托盘不可用时静默降级（trayIcon 保持 null，通知回退为弹窗）。
     */
    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            trayIcon = new TrayIcon(createTrayImage(), "本机助手");
            trayIcon.setImageAutoSize(true);
            PopupMenu menu = new PopupMenu();
            MenuItem showItem = new MenuItem("显示主窗口");
            showItem.addActionListener(e -> restoreFromTray());
            MenuItem exitItem = new MenuItem("退出");
            exitItem.addActionListener(e -> exitApp());
            menu.add(showItem);
            menu.addSeparator();
            menu.add(exitItem);
            trayIcon.setPopupMenu(menu);
            trayIcon.addActionListener(e -> restoreFromTray());
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception ignored) {
            trayIcon = null;
        }
    }

    /**
     * 自绘托盘位图：主色圆角方块 + 白色「本」字（16px，SansSerif 粗体）。
     * @return 绘制完成的 ARGB 位图
     */
    private static java.awt.Image createTrayImage() {
        int size = 16;
        var img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(UiTheme.PRIMARY);
            g.fillRoundRect(0, 0, size, size, 5, 5);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            FontMetrics fm = g.getFontMetrics();
            String glyph = "本";
            int tx = (size - fm.stringWidth(glyph)) / 2;
            int ty = (size - fm.getHeight()) / 2 + fm.getAscent() - 1;
            g.drawString(glyph, tx, ty);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * 注册关窗行为：开启「最小化到托盘」且托盘可用时隐藏窗口并仅首次弹出
     * 托盘气泡提示恢复方式；否则真正退出。
     */
    private void setupWindowClosing() {
        addWindowListener(new java.awt.event.WindowAdapter() {
            private boolean hintShown = false;
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (Config.getBool("minimizeToTray", true) && trayIcon != null) {
                    setVisible(false);
                    if (!hintShown) {
                        hintShown = true;
                        trayIcon.displayMessage("本机助手仍在后台运行",
                                "窗口已最小化到托盘，提醒与定时任务会继续执行。双击托盘图标可恢复，右键可退出。",
                                TrayIcon.MessageType.INFO);
                    }
                } else {
                    exitApp();
                }
            }
        });
    }

    /** 从托盘恢复主窗口：显示、还原最小化状态并置顶抢焦点。 */
    private void restoreFromTray() {
        setVisible(true);
        if (getState() == ICONIFIED) setState(NORMAL);
        toFront();
        requestFocus();
    }

    /**
     * 真正退出应用：守护线程随 JVM 结束，MCP 子进程由 shutdown hook 清理。
     */
    private void exitApp() {
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        System.exit(0);
    }

    /**
     * 单条提醒到期通知：优先托盘气泡；托盘不可用时退化为非模态弹窗，
     * 保证提醒在无托盘环境下仍然触达（不用 Dialog 阻塞调度线程）。
     * @param text 提醒内容
     */
    public void notifyReminder(String text) {
        if (trayIcon != null) {
            trayIcon.displayMessage("本机助手提醒", text, TrayIcon.MessageType.INFO);
        } else {
            JOptionPane.showMessageDialog(this, text, "本机助手提醒", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * 启动补发：把关机期间积压的多条到期提醒合并为一条托盘通知
     * （最多展示前 3 条，其余以数量概括），避免开屏连弹一串气泡。
     * @param reminders 首轮扫描到的全部到期提醒
     */
    public void notifyCatchUp(java.util.List<ReminderScheduler.Reminder> reminders) {
        if (reminders == null || reminders.isEmpty()) return;
        StringBuilder sb = new StringBuilder("你有 ").append(reminders.size()).append(" 条到期提醒：");
        int shown = Math.min(3, reminders.size());
        for (int i = 0; i < shown; i++) {
            String t = reminders.get(i).text();
            if (t.length() > 40) t = t.substring(0, 40) + "…";
            sb.append("\n· ").append(t);
        }
        if (reminders.size() > shown) sb.append("\n…等 ").append(reminders.size()).append(" 条");
        if (trayIcon != null) {
            trayIcon.displayMessage("本机助手提醒（未读补发）", sb.toString(), TrayIcon.MessageType.INFO);
        } else {
            JOptionPane.showMessageDialog(this, sb.toString(), "本机助手提醒（未读补发）",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ================= 发送 / 会话 =================
    private void doSend() {
        String text = input.getText().trim();
        if (text.isEmpty() || agent.isBusy()) return;
        input.setText("");
        chatPane.addUser(text);
        // 最后防线：发送链路的任何异常都要在界面可见，不允许 EDT 静默吞掉
        try {
            agent.sendMessage(text);
        } catch (Exception e) {
            chatPane.addError("发送失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void newConversation() {
        activeConvId = agent.newConversation();
        chatPane.reset();
        refreshConversations();
    }

    private void loadConv(String id) {
        var loaded = agent.loadConversation(id);
        if (loaded == null) return;
        activeConvId = id;
        chatPane.reset();
        for (var m : loaded.history()) {
            String role = String.valueOf(m.get("role"));
            String content = m.get("content") == null ? "" : String.valueOf(m.get("content"));
            switch (role) {
                case "user" -> chatPane.addUser(content);
                case "assistant" -> { if (!content.isBlank()) chatPane.addAssistantFinal(content); }
                case "tool" -> {
                    String tool = String.valueOf(m.getOrDefault("tool_name", "工具"));
                    chatPane.addToolHistory(tool, truncate(content, 90));
                }
            }
        }
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }

    // ================= 状态/列表 =================
    public void refreshStatus(boolean rebuildModels) {
        var s = ollama.getStatus();
        statusPill.setStatus(s.connected(), s.baseUrl());
        if (rebuildModels) {
            modelBox.putClientProperty("updating", true);
            String model = Config.getString("model", "");
            Object prev = modelBox.getSelectedItem();
            modelBox.removeAllItems();
            for (var m : s.models()) modelBox.addItem(String.valueOf(m.get("name")));
            if (model != null && !model.isBlank()) modelBox.setSelectedItem(model);
            else if (prev != null) modelBox.setSelectedItem(prev);
            modelBox.putClientProperty("updating", null);
        }
    }

    private void refreshConversations() {
        convIdToTitle.clear();
        convIds.clear();
        convModel.clear();
        int selected = -1, i = 0;
        for (var c : agent.listConversations()) {
            String id = String.valueOf(c.get("id"));
            String title = String.valueOf(c.get("title"));
            convIdToTitle.put(id, title);
            convIds.add(id);
            convModel.addElement(title);
            if (id.equals(activeConvId)) selected = i;
            i++;
        }
        if (selected >= 0) convList.setSelectedIndex(selected);
    }

    /**
     * 会话列表右键菜单：重命名 / 导出 Markdown / 删除。
     * 执行逻辑：右键按下时先选中所在行（与主流 IM/笔记应用一致），
     * 再弹出菜单；菜单动作按选中行对应的会话 ID 执行。
     */
    private void setupConversationMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem renameItem = new JMenuItem("重命名");
        JMenuItem exportItem = new JMenuItem("导出为 Markdown");
        JMenuItem deleteItem = new JMenuItem("删除会话");
        menu.add(renameItem);
        menu.add(exportItem);
        menu.addSeparator();
        menu.add(deleteItem);
        renameItem.addActionListener(e -> renameSelectedConversation());
        exportItem.addActionListener(e -> exportSelectedConversation());
        deleteItem.addActionListener(e -> deleteSelectedConversation());
        convList.setComponentPopupMenu(menu);
        convList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { selectAtPoint(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { selectAtPoint(e); }
            private void selectAtPoint(java.awt.event.MouseEvent e) {
                int idx = convList.locationToIndex(e.getPoint());
                if (idx >= 0 && !convList.isSelectedIndex(idx)) convList.setSelectedIndex(idx);
            }
        });
    }

    /**
     * 取列表当前选中行对应的会话 ID。
     * @return 会话 ID；无选中或索引越界时返回 null
     */
    private String selectedConversationId() {
        int i = convList.getSelectedIndex();
        if (i < 0 || i >= convIds.size()) return null;
        return convIds.get(i);
    }

    /**
     * 重命名当前选中会话：弹出预填旧标题的输入框，确认后写库并刷新列表。
     */
    private void renameSelectedConversation() {
        String id = selectedConversationId();
        if (id == null) return;
        String old = agent.conversationTitle(id);
        String name = (String) JOptionPane.showInputDialog(this, "输入新的会话名称：",
                "重命名会话", JOptionPane.PLAIN_MESSAGE, null, null, old == null ? "" : old);
        if (name == null) return;
        name = name.strip();
        if (name.isEmpty() || name.equals(old)) return;
        try {
            agent.renameConversation(id, name.length() > 40 ? name.substring(0, 40) : name);
            refreshConversations();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "重命名失败：" + ex.getMessage(),
                    "操作失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 导出当前选中会话为 .md 文件：弹保存框（默认名取标题并清洗文件名字符），
     * 以 UTF-8 写入用户选择的路径。
     */
    private void exportSelectedConversation() {
        String id = selectedConversationId();
        if (id == null) return;
        String md = agent.exportConversationMarkdown(id);
        if (md == null) {
            JOptionPane.showMessageDialog(this, "会话不存在或已被删除。",
                    "导出失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String title = agent.conversationTitle(id);
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File(safeFileName(title) + ".md"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            target = new java.io.File(target.getParentFile(), target.getName() + ".md");
        }
        try {
            java.nio.file.Files.writeString(target.toPath(), md,
                    java.nio.charset.StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, "已导出：\n" + target.getAbsolutePath(),
                    "导出成功", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "导出失败：" + ex.getMessage(),
                    "操作失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 删除当前选中会话：二次确认（明示不可恢复）；删除的是当前会话时
     * 自动新建空白会话并重置聊天区，避免界面停留在已删除内容上。
     */
    private void deleteSelectedConversation() {
        String id = selectedConversationId();
        if (id == null) return;
        String title = agent.conversationTitle(id);
        int ok = JOptionPane.showConfirmDialog(this,
                "确定删除会话「" + (title == null ? "" : title) + "」吗？此操作不可恢复。",
                "删除会话", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            agent.removeConversation(id);
            if (id.equals(activeConvId)) {
                activeConvId = agent.newConversation();
                chatPane.reset();
            }
            refreshConversations();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "删除失败：" + ex.getMessage(),
                    "操作失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 把会话标题清洗为合法文件名（Windows 非法字符替换为下划线，空名兜底）。
     * @param title 原始标题
     * @return 可用于文件名的非空字符串（不含扩展名）
     */
    private static String safeFileName(String title) {
        if (title == null || title.isBlank()) return "会话导出";
        String s = title.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        return (s.isEmpty() ? "会话导出" : (s.length() > 80 ? s.substring(0, 80) : s));
    }

    // ================= Agent 回调 =================
    public UiCallback callback() {
        return new UiCallback() {
            @Override public void onBusy(boolean busy) {
                SwingUtilities.invokeLater(() -> { sendBtn.setVisible(!busy); stopBtn.setVisible(busy); });
            }
            @Override public void onAssistantDelta(String chunk) {
                SwingUtilities.invokeLater(() -> chatPane.appendAssistantDelta(chunk));
            }
            @Override public void onAssistantDone(String finalText, Map<String, Long> stats) {
                SwingUtilities.invokeLater(() -> chatPane.finishAssistant());
            }
            @Override public void onWaiting() {
                SwingUtilities.invokeLater(() -> chatPane.showWaiting());
            }
            @Override public void onThinking(boolean active) {
                SwingUtilities.invokeLater(() -> chatPane.showThinking(active));
            }
            @Override public void onError(String message) {
                SwingUtilities.invokeLater(() -> chatPane.addError(message));
            }
            @Override public void onTool(ToolCard card) {
                SwingUtilities.invokeLater(() -> {
                    String preview = truncate(safeJson(card.args), 90);
                    chatPane.updateTool(card.id, card.tool, card.status, preview);
                    syncApprovalCard(card);
                });
            }
            @Override public void onConversationList() { SwingUtilities.invokeLater(MainFrame.this::refreshConversations); }
            @Override public void onReminder(Map<String, Object> reminder) {
                SwingUtilities.invokeLater(() -> notifyReminder(String.valueOf(reminder.get("text"))));
            }
        };
    }

    private void syncApprovalCard(ToolCard card) {
        synchronized (approvalLock) {
            if (card.waiting && approvalPanel.getClientProperty(card.id) == null) {
                approvalPanel.add(buildApprovalRow(card));
                approvalPanel.putClientProperty(card.id, Boolean.TRUE);
                approvalPanel.revalidate(); approvalPanel.repaint();
            } else if (!card.waiting && approvalPanel.getClientProperty(card.id) != null) {
                for (Component c : approvalPanel.getComponents()) {
                    if (card.id.equals(c.getName())) { approvalPanel.remove(c); break; }
                }
                approvalPanel.putClientProperty(card.id, null);
                approvalPanel.revalidate(); approvalPanel.repaint();
            }
        }
    }

    private JComponent buildApprovalRow(ToolCard card) {
        RoundedPanel row = new RoundedPanel(new BorderLayout(10, 0), UiTheme.RADIUS,
                UiTheme.WARNING_SOFT, new Color(0xE8D3A8), 1);
        row.setName(card.id);
        row.setBorder(new EmptyBorder(9, 13, 9, 13));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));

        String argsText = truncate(safeJson(card.args), 200);
        JLabel label = new JLabel("<html><b style='color:#8A6320'>需要确认</b>"
                + "&nbsp;&nbsp;<span style='color:#1F2329'>" + Html.esc(card.tool) + "</span>"
                + "<br><span style='color:#646A73'>" + Html.esc(argsText) + "</span></html>");
        label.setFont(UiTheme.font(12));
        row.add(label, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        FlatButton deny = FlatButton.ghost("拒绝");
        FlatButton allow = FlatButton.primary("允许");
        Runnable remove = () -> { approvalPanel.remove(row); approvalPanel.revalidate(); approvalPanel.repaint(); };
        deny.addActionListener(e -> { agent.respondApproval(card.id, false); remove.run(); });
        allow.addActionListener(e -> { agent.respondApproval(card.id, true); remove.run(); });
        btns.add(deny); btns.add(allow);
        row.add(btns, BorderLayout.EAST);
        return row;
    }

    private static String safeJson(Object o) {
        try { return com.localagent.util.Json.stringify(o); } catch (Exception e) { return ""; }
    }

    // ================= 状态胶囊 =================
    private static class StatusPill extends RoundedPanel {
        private static final long serialVersionUID = 1L;

        private final Dot dot = new Dot();
        private final JLabel label = new JLabel();

        StatusPill() {
            super(new FlowLayout(FlowLayout.LEFT, 7, 0), 8, UiTheme.CARD, UiTheme.BORDER, 1);
            setBorder(new EmptyBorder(4, 10, 4, 12));
            add(dot);
            label.setFont(UiTheme.font(12, Font.BOLD));
            add(label);
        }

        void setStatus(boolean connected, String url) {
            dot.setColor(connected ? UiTheme.SUCCESS : UiTheme.DANGER);
            label.setText((connected ? "已连接 " : "未连接 ") + url);
            label.setForeground(connected ? UiTheme.SUCCESS : UiTheme.DANGER);
        }
    }

    private static class Dot extends JComponent {
        private static final long serialVersionUID = 1L;

        private Color c = UiTheme.DANGER;
        Dot() { setPreferredSize(new Dimension(8, 8)); }
        void setColor(Color c) { this.c = c; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c);
            g2.fillOval(0, 0, 8, 8);
            g2.dispose();
        }
    }

    /** 供 Main 初始化时触发首次建会话后刷新。 */
    public void initialRefresh() {
        newConversation();
        refreshStatus(true);
    }
}
