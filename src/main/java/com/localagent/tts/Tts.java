package com.localagent.tts;

import com.localagent.tools.Proc;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * 本地语音合成（移植 tts.js）：借助 Windows System.Speech（SAPI）PowerShell 脚本，
 * 文本经 stdin 传入（避免命令行转义）。全程离线。
 */
public class Tts {
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
     * @return true=脚本正常退出（已朗读）；false=无文本/启动失败/超时
     */
    public boolean speak(String text) {
        String clean = text == null ? "" : stripEmoji(text).trim();
        if (clean.isEmpty()) return false;
        try {
            Path script = writeScript();
            Proc.Result r = Proc.exec("powershell.exe",
                    List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()),
                    null, 120000, clean);
            return r.code() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripEmoji(String s) {
        // 移除常见 emoji 与图形字符，避免 SAPI 读出乱码停顿
        return s.replaceAll("[\\p{So}\\p{Sk}\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\uFE0F]", "");
    }

    private Path writeScript() throws Exception {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "local-agent");
        Files.createDirectories(dir);
        Path f = dir.resolve("tts.ps1");
        Files.writeString(f, SCRIPT, StandardCharsets.UTF_8);
        return f;
    }
}
