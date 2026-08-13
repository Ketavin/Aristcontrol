package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;

/** Manual, user-directed live probe for the structured DashScope polish response. */
public final class QwenTextPolisherLiveProbe {

    private QwenTextPolisherLiveProbe() {
    }

    public static void main(String[] args) {
        String input = "嗯嗯，就是说这个这个方案吧，我觉得可能还要再看一下。第二个问题没有变化。";
        QwenTextPolisher polisher = new QwenTextPolisher(ModelConfig.getInstance());
        String output = polisher.polishOrOriginal(input, QwenTextPolisher.Mode.WORK);
        System.out.println("INPUT_LENGTH=" + input.length());
        System.out.println("OUTPUT_LENGTH=" + output.length());
        System.out.println("OUTPUT=" + output);
    }
}
