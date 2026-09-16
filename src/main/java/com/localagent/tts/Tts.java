package com.localagent.tts;

import com.localagent.toolkit.Proc;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * 本地语音合成（移植 tts.js）：借助 Windows System.Speech（SAPI）PowerShell 脚本，
 * 文本经 stdin 传入（避免命令行转义）。全程离线。
 *
 * 安全设计：
 * - 脚本文件使用 SecureRandom 随机文件名（tts-&lt;32 hex&gt;.ps1），不再使用固定可预测
 *   的 %TEMP%\local-agent\tts.ps1，杜绝同机低权限进程预占/替换脚本路径的竞争；
 * - 脚本内容为本类内置常量，无任何外部输入拼接，朗读文本只经 stdin 传递；
 * - 脚本执行后 best-effort 删除，不在临时目录留痕。
 */
public class Tts {
    /** 文件名随机数生成器（线程安全，仅用于临时脚本命名）。 */
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SCRIPT = """
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            if (-not (Add-Type -AssemblyName System.Speech -PassThru)) { exit 0 }
            $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
            try {
              $cv = @($synth.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'zh*' } | Select-Object -First 1)
              if ($cv) { $synth.SelectVoice($cv[0].VoiceInfo.Name) }
            } catch { }
            $text = [Console]::In.ReadToEnd()
            if ($text) { $synth.Speak($text) }
            $synth.Dispose()
            """;

    /**
     * 朗读文本。
     * 执行逻辑：清洗 emoji/空白 -> 写随机名临时脚本 -> 文本经 stdin 启动 PowerShell
     * 朗读 -> finally 中 best-effort 删除临时脚本（删除失败不影响朗读结果）。
     * @param text 待朗读文本；null 或空白时直接返回 false
     * @return true=脚本正常退出（已朗读）；false=无文本/启动失败/超时
     */
    public boolean speak(String text) {
        String clean = text == null ? "" : stripEmoji(text).trim();
        if (clean.isEmpty()) return false;
        Path script = null;
        try {
            script = writeScript();
            Proc.Result r = Proc.exec("powershell.exe",
                    List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()),
                    null, 120000, clean);
            return r.code() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            // 临时脚本即用即删；Windows 偶发文件句柄延迟，删除失败属可接受的 best-effort
            if (script != null) {
                try { Files.deleteIfExists(script); } catch (Exception ignored) {}
            }
        }
    }

    private static String stripEmoji(String s) {
        // 移除常见 emoji 与图形字符，避免 SAPI 读出乱码停顿
        return s.replaceAll("[\\p{So}\\p{Sk}\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\uFE0F]", "");
    }

    /**
     * 生成随机名临时 PowerShell 脚本并写入固定内容。
     * 文件名含 16 字节 SecureRandom 十六进制（32 字符），不可被同机进程预测，
     * 结合 {@link #speak} 末尾的即用即删，消除固定路径竞争窗口。
     * @return 已写入的脚本路径
     * @throws Exception 临时目录创建或文件写入失败时抛出（由 speak 统一兜底）
     */
    private Path writeScript() throws Exception {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "local-agent");
        Files.createDirectories(dir);
        byte[] seed = new byte[16];
        RANDOM.nextBytes(seed);
        Path f = dir.resolve("tts-" + HexFormat.of().formatHex(seed) + ".ps1");
        Files.writeString(f, SCRIPT, StandardCharsets.UTF_8);
        return f;
    }
}
