package com.localagent.ui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localagent.config.Config;
import com.localagent.knowledge.Knowledge;
import com.localagent.mcp.LocalToolchain;
import com.localagent.mcp.McpManager;
import com.localagent.mcp.McpManager.ServerStatus;
import com.localagent.mcp.McpToolCatalog;
import com.localagent.util.Json;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * 设置对话框（主题化）：圆角输入框、扁平按钮、统一间距。
 * 保存经 Config.set 白名单写入；baseUrl 由 OllamaClient 在下次请求时校验。
 *
 * MCP 区能力（配合离线 stdio 传输）：
 * 1. 编辑 mcpServers JSON，保存前做离线红线预检；
 * 2. 一键探测 npx/uvx/python 运行环境并插入常见本地 server 模板；
 * 3. 「测试连接」用编辑框内容做一次性握手验证，不写配置、不动正式连接；
 * 4. 保存时若 MCP 开关/配置有变化，后台调用 McpManager.reconcile 立即热生效，
 *    状态区逐服务展示结果，无需重启应用。
 *
 * 创建时间：2026-09-15，核心用途：全部本机可配置项的统一入口。
 */
final class SettingsDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JTextField baseUrl = new JTextField(28);
    private final JTextField model = new JTextField(28);
    private final JCheckBox requireConfirm = new JCheckBox("普通操作也需确认");
    private final JCheckBox memoryEnabled = new JCheckBox("启用记忆功能");
    private final JCheckBox knowledgeEnabled = new JCheckBox("启用知识库功能");
    private final JCheckBox mcpEnabled = new JCheckBox("启用 MCP 外部工具（仅本地 stdio 服务，不联网）");
    private final JTextArea mcpServers = new JTextArea(5, 34);
    private final JTextField knowledgeDir = new JTextField(28);
    private final JCheckBox thinking = new JCheckBox("深度思考（回答更透彻，但首字等待更久）");
    private final JTextField keepAliveMin = new JTextField(28);

    // ---- MCP 扩展区协作者与组件 ----
    private final transient McpManager mcpManager;
    private final transient McpToolCatalog mcpCatalog;
    private final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    private final JLabel envLabel = new JLabel(
            "<html>运行环境：<br>· 正在检测…<br>&nbsp;<br>&nbsp;</html>");
    private final FlatButton detectBtn = FlatButton.ghost("检测环境");
    private final JComboBox<String> templateBox = new JComboBox<>();
    private final FlatButton insertBtn = FlatButton.ghost("插入");
    private final FlatButton testBtn = FlatButton.ghost("测试连接");
    private final JTextArea mcpStatus = new JTextArea(3, 34);
    /** 最近一次环境探测对应的可用模板（与下拉框索引一致）。 */
    private transient List<LocalToolchain.Template> availableTemplates = List.of();

    /**
     * 创建设置对话框。
     * @param owner      父窗口
     * @param knowledge  知识库协作者（保存启用时重新 init）
     * @param mcpManager MCP 服务管理器（状态展示/热重连）
     * @param mcpCatalog MCP 工具目录（reconcile 的发布目标）
     */
    SettingsDialog(JFrame owner, Knowledge knowledge, McpManager mcpManager, McpToolCatalog mcpCatalog) {
        super(owner, "设置", true);
        this.mcpManager = mcpManager;
        this.mcpCatalog = mcpCatalog;
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(UiTheme.WINDOW);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(UiTheme.WINDOW);
        form.setBorder(new EmptyBorder(18, 20, 10, 20));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(7, 4, 7, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addRow(form, g, row++, "Ollama 地址：", styleField(baseUrl));
        addRow(form, g, row++, "模型名：", styleField(model));
        g.gridx = 1; g.gridy = row++; styleCheck(requireConfirm); form.add(requireConfirm, g);
        g.gridx = 1; g.gridy = row++; styleCheck(memoryEnabled); form.add(memoryEnabled, g);
        g.gridx = 1; g.gridy = row++; styleCheck(knowledgeEnabled); form.add(knowledgeEnabled, g);
        g.gridx = 1; g.gridy = row++; styleCheck(mcpEnabled); form.add(mcpEnabled, g);
        row = addMcpSection(form, g, row);
        g.gridx = 1; g.gridy = row++; styleCheck(thinking); form.add(thinking, g);
        addRow(form, g, row++, "模型保活（分钟）：", styleField(keepAliveMin));
        addRow(form, g, row++, "知识库目录：", styleField(knowledgeDir));
        add(form, BorderLayout.CENTER);

        FlatButton save = FlatButton.primary("保存");
        FlatButton cancel = FlatButton.ghost("取消");
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setBackground(UiTheme.WINDOW);
        btns.setBorder(new EmptyBorder(6, 0, 14, 16));
        btns.add(cancel); btns.add(save);
        add(btns, BorderLayout.SOUTH);

        loadValues();
        renderStatuses("当前连接状态：", mcpManager.statuses());

        save.addActionListener(e -> onSave(knowledge, save));
        cancel.addActionListener(e -> dispose());
        detectBtn.addActionListener(e -> detectEnvironment());
        insertBtn.addActionListener(e -> insertSelectedTemplate());
        testBtn.addActionListener(e -> testConnections());

        pack();
        setSize(520, Math.min(getHeight() + 40, 840));
        setLocationRelativeTo(owner);
        // 窗口显示后再后台探测，避免拖慢弹窗
        SwingUtilities.invokeLater(this::detectEnvironment);
    }

    /** 从 Config 加载全部字段到控件。 */
    private void loadValues() {
        baseUrl.setText(Config.getString("baseUrl", "http://127.0.0.1:11434"));
        model.setText(Config.getString("model", ""));
        requireConfirm.setSelected(Config.getBool("requireConfirm", true));
        memoryEnabled.setSelected(Config.getBool("memoryEnabled", false));
        knowledgeEnabled.setSelected(Config.getBool("knowledgeEnabled", false));
        mcpEnabled.setSelected(Config.getBool("mcpEnabled", false));
        mcpServers.setText(Config.getString("mcpServers", "{}"));
        thinking.setSelected(Config.getBool("thinking", false));
        knowledgeDir.setText(Config.getString("knowledgeDir", "D:/知识库"));
        // keepAlive 存储单位为毫秒，界面按分钟展示；0 表示用完立即卸载
        long keepMin = Math.round(Config.getInt("keepAlive", 1800000) / 60000.0);
        keepAliveMin.setText(String.valueOf(keepMin));
    }

    /**
     * 保存全部设置；MCP 开关/配置变化时后台热重连且保持窗口打开查看结果。
     * @param knowledge 知识库协作者
     * @param save      保存按钮（异步期间禁用）
     */
    private void onSave(Knowledge knowledge, FlatButton save) {
        ObjectNode p = Json.mapper().createObjectNode();
        String mcpJson;
        boolean mcpChanged;
        try {
            int minutes = parseNonNegativeInt(keepAliveMin.getText().trim(), 30);
            p.put("baseUrl", baseUrl.getText().trim());
            p.put("model", model.getText().trim());
            p.put("requireConfirm", requireConfirm.isSelected());
            p.put("memoryEnabled", memoryEnabled.isSelected());
            p.put("knowledgeEnabled", knowledgeEnabled.isSelected());
            p.put("mcpEnabled", mcpEnabled.isSelected());
            // 保存前做 JSON 与离线红线预检；运行时 McpManager 还会再做一次权威校验
            mcpJson = mcpServers.getText().trim();
            validateMcpServersJson(mcpJson);
            p.put("mcpServers", mcpJson);
            p.put("thinking", thinking.isSelected());
            p.put("keepAlive", minutes * 60000);
            p.put("knowledgeDir", knowledgeDir.getText().trim());
            mcpChanged = mcpEnabled.isSelected() != Config.getBool("mcpEnabled", false)
                    || !mcpJson.equals(Config.getString("mcpServers", "{}"));
            Config.set(p);
            if (knowledgeEnabled.isSelected()) knowledge.init(knowledgeDir.getText().trim());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "保存失败：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!mcpChanged) { dispose(); return; }
        // MCP 有变更：后台 reconcile，状态区实时展示，窗口保留供用户确认结果
        final boolean enabled = mcpEnabled.isSelected();
        runAsync(save, "正在保存并重连 MCP 服务…", () -> {
            List<ServerStatus> st = mcpManager.reconcile(mcpCatalog);
            SwingUtilities.invokeLater(() -> {
                if (!enabled) mcpStatus.setText("已保存：MCP 外部工具已停用，全部本地子进程已关闭。");
                else renderStatuses("已保存并重连：", st);
            });
        });
    }

    /** 后台探测 npx/uvx/python，完成后在 EDT 刷新环境行与模板下拉。 */
    private void detectEnvironment() {
        runAsync(detectBtn, "正在检测本机运行环境…", () -> {
            List<LocalToolchain.Probe> ps =
                    LocalToolchain.probe(LocalToolchain.defaultRunner(), windows);
            SwingUtilities.invokeLater(() -> {
                envLabel.setText(renderProbes(ps));
                rebuildTemplateBox(ps);
                // 探测期间忙碌文案占了状态区，完成后恢复正式连接状态
                renderStatuses("当前连接状态：", mcpManager.statuses());
            });
        });
    }

    /**
     * 把探测结果渲染为多行 HTML（每个环境一行，版本号截断防止横向溢出）。
     * @param ps 探测结果
     * @return HTML 文本
     */
    private String renderProbes(List<LocalToolchain.Probe> ps) {
        StringBuilder sb = new StringBuilder("<html>运行环境：");
        for (LocalToolchain.Probe p : ps) {
            sb.append("<br>· ").append(p.display()).append("：");
            if (p.available()) {
                String v = p.version();
                if (v.length() > 24) v = v.substring(0, 24) + "…";
                sb.append("√ ").append(escapeHtml(v));
            } else {
                sb.append("× 未检测到");
            }
        }
        return sb.append("</html>").toString();
    }

    /**
     * HTML 文本转义（版本输出来自外部进程，不可信）。
     * @param s 原始文本
     * @return 转义后文本
     */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 依据可用运行环境重建模板下拉与可插入列表。
     * @param ps 探测结果
     */
    private void rebuildTemplateBox(List<LocalToolchain.Probe> ps) {
        availableTemplates = LocalToolchain.availableTemplates(ps, windows);
        templateBox.removeAllItems();
        if (availableTemplates.isEmpty()) {
            templateBox.addItem("未检测到 npx/uvx，可手动编辑 JSON");
            insertBtn.setEnabled(false);
        } else {
            for (LocalToolchain.Template t : availableTemplates) templateBox.addItem(templateDisplay(t));
            insertBtn.setEnabled(true);
        }
    }

    /**
     * 模板下拉项展示文本。
     * @param t 模板
     * @return 短名 + 依赖环境
     */
    private String templateDisplay(LocalToolchain.Template t) {
        String shortName = switch (t.id()) {
            case "filesystem" -> "本地文件系统";
            case "memory" -> "本地记忆图谱";
            case "time" -> "时区时间";
            default -> t.id();
        };
        return t.serverName() + "（" + t.toolchainKey() + " · " + shortName + "）";
    }

    /** 把下拉框选中模板插入 JSON 编辑框（重名自动加后缀），失败弹错不关窗。 */
    private void insertSelectedTemplate() {
        int idx = templateBox.getSelectedIndex();
        if (idx < 0 || idx >= availableTemplates.size()) return;
        try {
            LocalToolchain.InsertResult r =
                    LocalToolchain.insertTemplate(mcpServers.getText(), availableTemplates.get(idx));
            mcpServers.setText(r.json());
            mcpStatus.setText("已插入模板「" + r.serverName()
                    + "」。可按需修改参数（如目录路径），再点「测试连接」或保存。");
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "无法插入模板", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 用编辑框当前 JSON 做一次性连接测试（不保存、不影响正式连接）。 */
    private void testConnections() {
        String json = mcpServers.getText().trim();
        try {
            validateMcpServersJson(json);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "无法测试", JOptionPane.WARNING_MESSAGE);
            return;
        }
        runAsync(testBtn, "正在测试连接（每个服务最多约 30 秒）…", () -> {
            List<ServerStatus> st = McpManager.testConnections(json);
            SwingUtilities.invokeLater(() -> renderStatuses("测试结果（尚未保存）：", st));
        });
    }

    /**
     * 把逐服务状态渲染到只读状态区。
     * @param prefix 标题行（说明状态来源）
     * @param st     状态列表
     */
    private void renderStatuses(String prefix, List<ServerStatus> st) {
        StringBuilder sb = new StringBuilder(prefix);
        if (st == null || st.isEmpty()) {
            sb.append("暂无 MCP 服务记录。");
        } else {
            for (ServerStatus s : st) {
                String mark = switch (s.state()) {
                    case CONNECTED -> "[√]";
                    case FAILED -> "[×]";
                    case REJECTED -> "[!]";
                    case SKIPPED -> "[-]";
                };
                sb.append('\n').append(mark).append(' ').append(s.name()).append(" — ").append(s.detail());
            }
        }
        mcpStatus.setText(sb.toString());
        mcpStatus.setCaretPosition(0);
    }

    /**
     * 在后台守护线程执行 MCP 相关任务，期间禁用触发按钮并在状态区显示忙碌文案；
     * 任何异常都兜底显示，不让后台线程静默死掉。
     * @param trigger  触发按钮（完成后恢复可用）
     * @param busyText 忙碌文案
     * @param task     后台任务（内部如需更新 UI 自行 invokeLater）
     */
    private void runAsync(AbstractButton trigger, String busyText, Runnable task) {
        trigger.setEnabled(false);
        mcpStatus.setText(busyText);
        Thread th = new Thread(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        mcpStatus.setText("执行出错：" + ex.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> trigger.setEnabled(true));
            }
        }, "mcp-settings");
        th.setDaemon(true);
        th.start();
    }

    private JTextField styleField(JTextField f) {
        f.setFont(UiTheme.font(13));
        f.setBorder(new UiTheme.RoundedBorder(UiTheme.BORDER_STRONG, 1, 7));
        f.setPreferredSize(new Dimension(280, 32));
        return f;
    }

    /**
     * 解析非负整数分钟数输入。
     * @param s    用户输入文本；空白时返回默认值
     * @param dflt 空白输入时采用的默认分钟数
     * @return 0~1440（上限 24 小时，防止模型长期占用显存及毫秒换算溢出）
     * @throws IllegalArgumentException 输入不是整数或为负数时抛出（由保存按钮统一弹框提示）
     */
    private int parseNonNegativeInt(String s, int dflt) {
        if (s == null || s.isBlank()) return dflt;
        int v;
        try { v = Integer.parseInt(s); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("模型保活分钟数必须是 0 或正整数。");
        }
        if (v < 0) throw new IllegalArgumentException("模型保活分钟数不能为负数。");
        return Math.min(v, 24 * 60);
    }

    /**
     * 保存前预检 MCP 服务 JSON：必须是对象；每条服务必须有字符串 command；
     * 出现网络型传输（type 非 stdio）或 url 类字段直接拒绝，守住离线红线。
     * @param json 用户输入的配置文本
     * @throws IllegalArgumentException 空文本/JSON 非法/含远程配置/缺 command 时抛出
     */
    private void validateMcpServersJson(String json) {
        if (json == null || json.isBlank()) return; // 允许留空（等同无服务）
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = Json.mapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP 服务配置不是合法 JSON：" + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("MCP 服务配置必须是 JSON 对象，如 {\"服务名\":{\"command\":\"...\"}}。");
        }
        var it = root.fields();
        while (it.hasNext()) {
            var en = it.next();
            String n = en.getKey();
            var node = en.getValue();
            if (!node.isObject()) throw new IllegalArgumentException("MCP 服务「" + n + "」配置必须是对象。");
            String type = node.path("type").asText("stdio").toLowerCase();
            if (!"stdio".equals(type) || node.has("url") || node.has("serverUrl") || node.has("endpoint")) {
                throw new IllegalArgumentException("离线模式仅支持本地 stdio 服务，服务「" + n + "」含网络传输/地址配置。");
            }
            if (node.path("command").asText("").isBlank()) {
                throw new IllegalArgumentException("MCP 服务「" + n + "」缺少 command（本地可执行文件路径或命令名）。");
            }
        }
    }

    private void styleCheck(JCheckBox c) {
        c.setFont(UiTheme.font(13));
        c.setForeground(UiTheme.TEXT);
        c.setBackground(UiTheme.WINDOW);
        c.setFocusPainted(false);
    }

    private void addRow(JPanel form, GridBagConstraints g, int row, String label, JComponent field) {
        g.gridwidth = 1; g.weightx = 0; g.gridx = 0; g.gridy = row;
        JLabel l = new JLabel(label);
        l.setFont(UiTheme.font(13));
        l.setForeground(UiTheme.TEXT_MUTED);
        form.add(l, g);
        g.gridx = 1; g.weightx = 1;
        form.add(field, g);
    }

    /**
     * 构建 MCP 配置区：说明、JSON 编辑框、环境探测行、模板/测试行、状态区。
     * @param form 表单面板
     * @param g    布局约束（方法内固定使用第二列、水平填充）
     * @param row  起始行号
     * @return 该区占用后的下一空行号
     */
    private int addMcpSection(JPanel form, GridBagConstraints g, int row) {
        g.gridx = 1; g.gridwidth = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;

        JLabel hint = new JLabel("<html>MCP 本地服务（JSON，仅 stdio；不支持 http/url 远程服务）。"
                + "可先「检测环境」再插入模板；首次用 npx/uvx 拉取服务包需自行在有网环境预热一次。</html>");
        hint.setFont(UiTheme.font(11));
        hint.setForeground(UiTheme.TEXT_MUTED);
        g.gridy = row++;
        form.add(hint, g);

        mcpServers.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        mcpServers.setLineWrap(true);
        mcpServers.setBorder(new UiTheme.RoundedBorder(UiTheme.BORDER_STRONG, 1, 7));
        JScrollPane jsonScroll = new JScrollPane(mcpServers);
        jsonScroll.setPreferredSize(new Dimension(400, 84));
        jsonScroll.setBorder(new UiTheme.RoundedBorder(UiTheme.BORDER_STRONG, 1, 7));
        g.gridy = row++;
        form.add(jsonScroll, g);

        // 环境探测行：左侧多行结果（每环境一行，避免长版本号横向溢出），右侧检测按钮
        JPanel envRow = new JPanel(new BorderLayout(10, 0));
        envRow.setOpaque(false);
        envLabel.setFont(UiTheme.font(11));
        envLabel.setForeground(UiTheme.TEXT_MUTED);
        envRow.add(envLabel, BorderLayout.CENTER);
        JPanel detectBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        detectBox.setOpaque(false);
        detectBox.add(detectBtn);
        envRow.add(detectBox, BorderLayout.EAST);
        g.gridy = row++;
        form.add(envRow, g);

        // 模板选择 + 插入一行（控件收窄，防止按钮被挤出可视区）
        JPanel tplRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tplRow.setOpaque(false);
        JLabel tplLabel = new JLabel("模板：");
        tplLabel.setFont(UiTheme.font(12));
        tplLabel.setForeground(UiTheme.TEXT_MUTED);
        templateBox.setPreferredSize(new Dimension(150, 28));
        tplRow.add(tplLabel);
        tplRow.add(templateBox);
        tplRow.add(insertBtn);
        g.gridy = row++;
        form.add(tplRow, g);

        // 测试连接独占一行
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        testRow.setOpaque(false);
        testRow.add(testBtn);
        g.gridy = row++;
        form.add(testRow, g);

        // 只读状态区
        mcpStatus.setFont(UiTheme.font(12));
        mcpStatus.setEditable(false);
        mcpStatus.setLineWrap(true);
        mcpStatus.setWrapStyleWord(true);
        mcpStatus.setBackground(UiTheme.SIDEBAR);
        mcpStatus.setForeground(UiTheme.TEXT_MUTED);
        mcpStatus.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane statusScroll = new JScrollPane(mcpStatus);
        statusScroll.setPreferredSize(new Dimension(400, 76));
        statusScroll.setBorder(new UiTheme.RoundedBorder(UiTheme.BORDER, 1, 7));
        g.gridy = row++;
        form.add(statusScroll, g);
        return row;
    }
}
