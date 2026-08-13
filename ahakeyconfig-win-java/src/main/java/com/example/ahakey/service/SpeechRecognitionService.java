package com.example.ahakey.service;

/**
 * Common contract for local and cloud speech-recognition providers.
 */
public interface SpeechRecognitionService {

    @FunctionalInterface
    interface Consumer<T> {
        void accept(T value);
    }

    void initialize() throws Exception;

    void startListening(
        Consumer<String> onPartial,
        Consumer<String> onFinal,
        Consumer<String> onError
    );

    void stopListening();

    boolean isVadEnabled();

    String addPunctuation(String text);

    String getProviderDisplayName();

    void release();
}
