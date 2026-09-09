package io.yupiik.ia.agent.tui;

import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;

/**
 * The key binding presets exposed by the TUI, each mapping one-to-one to the
 * predefined {@link BindingSets} factories. The default is {@link #STANDARD}
 * which only binds navigation to the arrow keys and stays safe for text input.
 */
public enum KeyBindingPreset {
    STANDARD, VIM, EMACS, INTELLIJ, VSCODE;

    /**
     * @return the library bindings for this preset ({@link BindingSets})
     */
    public Bindings toBindings() {
        return switch (this) {
            case STANDARD -> BindingSets.standard();
            case VIM -> BindingSets.vim();
            case EMACS -> BindingSets.emacs();
            case INTELLIJ -> BindingSets.intellij();
            case VSCODE -> BindingSets.vscode();
        };
    }
}