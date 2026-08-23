package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;

/** Manual, user-directed live probe for the structured DashScope polish response. */
public final class QwenTextPolisherLiveProbe {

    private QwenTextPolisherLiveProbe() {
    }

    public static void main(String[] args) {
        String input = args.length > 0
            ? args[0]
            : "嗯嗯，就是说这个这个方案吧，我觉得可能还要再看一下。第二个问题没有变化。";
        QwenTextPolisher.Mode mode = args.length > 1
            ? QwenTextPolisher.Mode.valueOf(args[1].toUpperCase(java.util.Locale.ROOT))
            : QwenTextPolisher.Mode.WORK;
        QwenTextPolisher polisher = new QwenTextPolisher(ModelConfig.getInstance());
        QwenTextPolisher.PolishResult result = polisher.polishWithResult(input, mode);
        String output = result.text();
        System.out.println("INPUT_LENGTH=" + input.length());
        System.out.println("OUTPUT_LENGTH=" + output.length());
        System.out.println("MODE=" + result.mode());
        System.out.println("OUTCOME=" + result.outcome());
        System.out.println("ACCEPTED=" + result.acceptedSegments() + "/" + result.totalSegments());
        System.out.println("REASON=" + result.reason());
        System.out.println("OUTPUT=" + output);
    }
}
