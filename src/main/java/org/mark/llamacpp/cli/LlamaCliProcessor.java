package org.mark.llamacpp.cli;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import org.mark.llamacpp.server.LlamaCppProcess;

/**
 * 	用来运行CLI。转移到{@link LlamaCppProcessOld}
 */
@Deprecated
public class LlamaCliProcessor {
	
	
	/**
	 * 	cli程序的路径
	 */
	private String bin;
	
	
	
	public LlamaCliProcessor(String bin) {
		
		
	}
	
	
	
	
	
	public static void main(String[] args) {
		
		String llamaExe = "C:\\Users\\Mark\\App\\llama.cpp\\llama-cli.exe";
        String modelPath = "C:\\Users\\Mark\\Models\\GGUF\\Qwen3-0.6B-Q8_0\\Qwen3-0.6B-Q8_0.gguf";

		ProcessBuilder pb = new ProcessBuilder(llamaExe, "-m", modelPath, "-c", "8192");
		pb.redirectErrorStream(true);
		try {
			System.out.println("🚀 正在启动 llama-cli.exe...");
			Process process = pb.start();
			// 获取输入/输出流
			OutputStream stdin = process.getOutputStream();
			BufferedReader stdout = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8));
			System.out.println("✅ llama-cli 已启动，现在可以输入问题了（输入 'quit' 或 'exit' 退出）：\n");
			// 创建一个 Scanner 读取用户键盘输入
			Scanner scanner = new Scanner(System.in);
			// 使用虚拟线程异步读取模型输出（Java 21 特性）
			Thread.ofVirtual().start(() -> {
				String line;
				try {
					while ((line = stdout.readLine()) != null) {
						System.out.println("[LLAMA] " + line);
					}
				} catch (IOException e) {
					// 进程关闭时正常退出
					System.err.println("❌ 输出流已关闭");
				}
			});//.setDaemon(true); // 设置为守护线程，主线程结束自动终止
			// 主线程：读取用户输入并发送给 llama-cli
			String userInput;
			while (true) {
				System.out.print("\n你: ");
				userInput = scanner.nextLine();
				if ("quit".equalsIgnoreCase(userInput) || "exit".equalsIgnoreCase(userInput)) {
					System.out.println("👋 退出中...");
					break;
				}
				// 发送用户输入到 llama-cli（注意：llama-cli 通常期望 \n 结尾）
				try {
					writer.write(userInput + "\n");
					writer.flush(); // ⚠️ 必须 flush！否则无反应
				} catch (IOException e) {
					System.err.println("❌ 向 llama-cli 发送输入失败：" + e.getMessage());
					break;
				}
			}
			// 关闭输入流，通知进程结束
			writer.close();
			// 等待进程退出（最多10秒）
			boolean terminated = process.waitFor(10, TimeUnit.SECONDS);
			if (!terminated) {
				System.err.println("⚠️ 进程未正常退出，强制终止...");
				process.destroyForcibly();
			}
			scanner.close();
			int exitCode = process.exitValue();
			System.out.println("🏁 llama-cli 退出码: " + exitCode);
		} catch (IOException e) {
			System.err.println("❌ 启动 llama-cli.exe 失败，请检查路径是否存在：");
			e.printStackTrace();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); // 恢复中断状态
			System.err.println("程序被中断");
		} finally {
			System.out.println("👋 程序已退出。");
		}
	}
	
	
	
	
}
