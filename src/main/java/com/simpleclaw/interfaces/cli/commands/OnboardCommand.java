package com.simpleclaw.interfaces.cli.commands;

import com.simpleclaw.config.ConfigLoader;
import com.simpleclaw.config.model.*;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.nio.file.Files;

/**
 * onboard 子命令：引导创建 ~/.simpleclaw 目录与默认 config.json（若不存在）。
 */
@Slf4j
@CommandLine.Command(name = "onboard", description = "初始化配置与工作区（~/.simpleclaw）")
public class OnboardCommand implements Runnable {

    @Override
    public void run() {
        try {
            Files.createDirectories(ConfigLoader.getDataDir());
            Files.createDirectories(ConfigLoader.getDefaultWorkspacePath());
            Files.createDirectories(ConfigLoader.getSessionsDir());
            if (!Files.exists(ConfigLoader.getConfigPath())) {
                com.simpleclaw.config.Config config = new com.simpleclaw.config.Config();
                AgentsConfig agents = new AgentsConfig();
                agents.setWorkspace(ConfigLoader.getDefaultWorkspacePath().toString());
                agents.setModel("gpt-3.5-turbo");
                agents.setDefaultProvider("openai");
                config.setAgents(agents);
                java.util.Map<String, ProviderConfig> map = new java.util.HashMap<>();
                ProviderConfig p = new ProviderConfig();
                p.setApiKey(System.getenv().getOrDefault("OPENAI_API_KEY", ""));
                p.setApiBase("https://api.openai.com/v1");
                map.put("openai", p);
                config.setProviders(map);
                config.setChannels(new ChannelsConfig());
                config.setGateway(new GatewayConfig());
                config.setTools(new ToolsConfig());
                ConfigLoader.saveConfig(config);
                log.info("Created {}. Please set agents.model and providers.openai.apiKey.", ConfigLoader.getConfigPath());
            } else {
                log.info("Config already exists: {}", ConfigLoader.getConfigPath());
            }
        } catch (Exception e) {
            log.error("Onboard failed: {}", e.getMessage());
        }
    }
}
