package com.mark.test.tools;

import java.io.IOException;
import java.nio.file.Path;

import org.mark.llamacpp.record.BinaryRequestLog;

import com.google.gson.Gson;

public class RequestLogTest {



	public static void main(String[] args) throws IOException {

		Gson gson = new Gson();
		
		BinaryRequestLog log = new BinaryRequestLog(Path.of("cache/record/test.requests.bin"));

		System.err.println(gson.toJson(log.readRecord(0)));
		
		//log.appendFromJson("{\"requestId\":\"c0558bbe-3875-467f-9871-438596d1bc8d\",\"modelId\":\"Qwen3.5-0.8B-Q4_K_M\",\"endpoint\":\"/v1/chat/completions\",\"startTime\":1778838912690,\"elapsedMs\":3941,\"status\":\"CREATED\",\"phase\":\"GENERATION\",\"timing\":{\"cache_n\":0,\"prompt_n\":16,\"prompt_ms\":1717.516,\"prompt_per_token_ms\":107.34475,\"prompt_per_second\":9.315779299872606,\"predicted_n\":66,\"predicted_ms\":2169.261,\"predicted_per_token_ms\":32.86759090909091,\"predicted_per_second\":30.425107905411107}}");

		log.close();
	}



}
