package org.mark.llamacpp.server.service;

import com.google.gson.JsonObject;

import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU服务
 * 启动时探测系统，检查 nvidia-smi / amd-smi 是否可用，查询时返回原始命令输出
 */
public class GpuService {

    private static final Logger logger = LoggerFactory.getLogger(GpuService.class);
    private static final GpuService INSTANCE = new GpuService();

    private final String osType;

    private final boolean nvidiaSmiAvailable;
    private final boolean amdSmiAvailable;

    private final String nvidiaSmiInitOutput;
    private final String amdSmiInitOutput;

    public static GpuService getInstance() {
        return INSTANCE;
    }

    private GpuService() {
        String osName = System.getProperty("os.name").toLowerCase();
        this.osType = osName.contains("win") ? "windows" :
                       osName.contains("mac") ? "mac" : "linux";

        // 探测 nvidia-smi（Windows 和 Linux 均支持）
        CommandLineRunner.CommandResult nvidiaResult = probeCommand(new String[]{"nvidia-smi"}, 5);
        this.nvidiaSmiAvailable = nvidiaResult != null;
        this.nvidiaSmiInitOutput = nvidiaResult != null ? nvidiaResult.getOutput() : null;

        // 探测 amd-smi（仅 Linux）
        if ("linux".equals(this.osType)) {
            CommandLineRunner.CommandResult amdResult = probeCommand(new String[]{"amd-smi"}, 5);
            this.amdSmiAvailable = amdResult != null;
            this.amdSmiInitOutput = amdResult != null ? amdResult.getOutput() : null;
        } else {
            this.amdSmiAvailable = false;
            this.amdSmiInitOutput = null;
        }

        logger.info("GPU服务初始化: os={}, nvidia-smi={}, amd-smi={}",
                this.osType, nvidiaSmiAvailable ? "可用" : "不可用", amdSmiAvailable ? "可用" : "不可用");
    }

    /**
     * 探测命令是否可用，可用则返回 CommandResult，不可用返回 null
     */
    private CommandLineRunner.CommandResult probeCommand(String[] cmd, int timeoutSeconds) {
        CommandLineRunner.CommandResult result = CommandLineRunner.execute(cmd, timeoutSeconds);
        if (result == null || result.getExitCode() == null || result.getExitCode() != 0) {
            return null;
        }
        String output = result.getOutput();
        if (output == null || output.trim().isEmpty()) {
            return null;
        }
        return result;
    }

    /**
     * 获取GPU信息（初始化时的快照）
     */
    public JsonObject getServiceInfo() {
        return buildJson(this.nvidiaSmiInitOutput, this.amdSmiInitOutput);
    }

    /**
     * 查询GPU实时状态（重新执行命令）
     */
    public JsonObject queryGpuStatus() {
        String nvidiaOutput = null;
        if (this.nvidiaSmiAvailable) {
            CommandLineRunner.CommandResult r = probeCommand(new String[]{"nvidia-smi"}, 5);
            if (r != null) {
                nvidiaOutput = r.getOutput();
            }
        }

        String amdOutput = null;
        if (this.amdSmiAvailable) {
            CommandLineRunner.CommandResult r = probeCommand(new String[]{"amd-smi"}, 5);
            if (r != null) {
                amdOutput = r.getOutput();
            }
        }

        return buildJson(nvidiaOutput, amdOutput);
    }

    private JsonObject buildJson(String nvidiaOutput, String amdOutput) {
        JsonObject result = new JsonObject();
        result.addProperty("os", this.osType);

        JsonObject nvidia = new JsonObject();
        nvidia.addProperty("available", this.nvidiaSmiAvailable);
        if (nvidiaOutput != null) {
            nvidia.addProperty("output", nvidiaOutput);
        } else {
            nvidia.addProperty("output", (String) null);
        }
        result.add("nvidia-smi", nvidia);

        JsonObject amd = new JsonObject();
        amd.addProperty("available", this.amdSmiAvailable);
        if (amdOutput != null) {
            amd.addProperty("output", amdOutput);
        } else {
            amd.addProperty("output", (String) null);
        }
        result.add("amd-smi", amd);

        return result;
    }
}
