package com.medieval.game.enums;

// goldPerHour agora representa bronze por hora
public enum WorkType {

    TAVERN_HELPER  ("Ajudante da Taverna",       "Serve mesas e lava pratos na taverna local.",             15,  0,  3),
    STABLE_KEEPER  ("Cuidador dos Estábulos",    "Alimenta e cuida dos cavalos da cidade.",                 20,  0,  4),
    GOODS_CARRIER  ("Carregador de Mercadorias", "Transporta mercadorias pelo mercado da cidade.",           30,  1,  6),
    SMITH_ASSISTANT("Ajudante do Ferreiro",      "Auxilia o ferreiro a forjar ferramentas e armaduras.",    45,  2,  8),
    NOBLE_GUARD    ("Guarda da Nobreza",         "Protege as propriedades e rotas da nobreza local.",       65,  3, 12),
    LOCAL_MERCENARY("Mercenário Local",          "Realiza serviços militares para comerciantes ricos.",    100,  5, 18);

    public final String displayName;
    public final String description;
    public final int goldPerHour;   // bronze por hora
    public final int minWorkLevel;
    public final int xpPerHour;

    WorkType(String displayName, String description, int goldPerHour, int minWorkLevel, int xpPerHour) {
        this.displayName  = displayName;
        this.description  = description;
        this.goldPerHour  = goldPerHour;
        this.minWorkLevel = minWorkLevel;
        this.xpPerHour    = xpPerHour;
    }
}
