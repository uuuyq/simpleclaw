package com.simpleclaw.interfaces.cli.commands;

import com.simpleclaw.config.ConfigLoader;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.nio.file.Files;

/**
 * status 子命令：打印配置路径、工作区、数据目录是否存在等状态。
 */
@Slf4j
@CommandLine.Command(name = "status", description = "查看配置与工作区状态")
public class StatusCommand implements Runnable {

    @Override
    public void run() {
        log.info("Config:  {} (exists: {})", ConfigLoader.getConfigPath(), Files.exists(ConfigLoader.getConfigPath()));
        log.info("DataDir:  {} (exists: {})", ConfigLoader.getDataDir(), Files.exists(ConfigLoader.getDataDir()));
        log.info("Workspace: {} (exists: {})", ConfigLoader.getDefaultWorkspacePath(), Files.exists(ConfigLoader.getDefaultWorkspacePath()));
        log.info("Sessions: {} (exists: {})", ConfigLoader.getSessionsDir(), Files.exists(ConfigLoader.getSessionsDir()));
    }
}
