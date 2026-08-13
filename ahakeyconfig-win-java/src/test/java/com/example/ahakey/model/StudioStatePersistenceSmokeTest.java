package com.example.ahakey.model;

/** Standalone smoke test for user-facing voice polish preference persistence. */
public final class StudioStatePersistenceSmokeTest {

    private StudioStatePersistenceSmokeTest() {
    }

    public static void main(String[] args) {
        StudioState original = new StudioState();
        original.toggleAhaType(false);

        StudioState restored = new StudioState();
        restored.loadFromPersisted(original.toPersisted());

        require(!restored.isAhaTypeEnabled(), "Disabled text polish preference was not restored");
        require(
            StudioState.PersistedDraft.defaults().ahaTypeEnabled,
            "New installs must default to text polish enabled"
        );
        System.out.println("Studio state persistence smoke test passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
