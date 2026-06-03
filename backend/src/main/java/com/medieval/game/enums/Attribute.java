package com.medieval.game.enums;

public enum Attribute {
    STRENGTH    ("Força (STR)",        "⚔",  "+1 ATK/pt · Attack Bonus floor(STR/20) no d20 · cap 60"),
    DEXTERITY   ("Destreza (DEX)",     "🛡",  "+1 AC/pt · AC = 10 + DEX · torna mais difícil de acertar · cap 40"),
    CONSTITUTION("Constituição (CON)", "❤",  "+8 HP/pt · sem cap · razão de upar além do nível 95"),
    LUCK        ("Sorte (LUK)",        "🍀", "+1% drop · expande janela de crítico · Fortune Save · cap 50"),
    INTELLECT   ("Intelecto (INT)",    "📚", "+0.5% Smithing · -0.2% custo treino · +0.3% yield coleta · cap 40");

    public final String displayName;
    public final String icon;
    public final String effect;

    Attribute(String displayName, String icon, String effect) {
        this.displayName = displayName;
        this.icon        = icon;
        this.effect      = effect;
    }
}
