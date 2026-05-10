package com.mark.test.tools;

import java.io.File;
import java.io.IOException;

import org.mark.llamacpp.gguf.GGUFMetaDataReader;
public class MtpDetectTest {
    public static void main(String[] args) throws IOException {

        
        args = new String[] {"extract", "/home/mark/MTP/newone.gguf", "/home/mark/App/llama.cpp/mtp-java.gguf"};
        
        String mode = args[0];
        File input = new File(args[1]);
        if (!input.exists()) {
            System.err.println("File not found: " + args[1]);
            System.exit(1);
        }

        if ("check".equals(mode)) {
            GGUFMetaDataReader.MtpInfo info = GGUFMetaDataReader.extractMtpInfo(input);
            if (info.hasMtp()) {
                System.out.println("MTP found!");
                System.out.println("  Architecture:  " + info.architecture());
                System.out.println("  Block count:   " + info.blockCount());
                System.out.println("  MTP layers:    " + info.nextnPredictLayers());
                System.out.println("  Trunk blocks:  " + info.trunkCount());
                System.out.println("  MTP prefixes:  " + info.mtpBlockPrefixes());
            } else {
                System.out.println("No MTP layers.");
            }
        } else if ("extract".equals(mode)) {
            if (args.length < 3) {
                System.err.println("Usage: java MtpTool.java extract <model.gguf> <output.gguf>");
                System.exit(1);
            }
            File output = new File(args[2]);
            GGUFMetaDataReader.extractMtpDonor(input, output);
            System.out.println("Donor written: " + output);
        } else {
            System.err.println("Unknown mode: " + mode);
            System.exit(1);
        }
    }
}
