package com.simpleclaw.cli;

import picocli.CommandLine;

/**
 * 根命令：无参数时打印帮助；子命令由 Main 注册。
 */
@CommandLine.Command(name = "simpleclaw", mixinStandardHelpOptions = true,
        description = " AI 助手")
public class RootCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
