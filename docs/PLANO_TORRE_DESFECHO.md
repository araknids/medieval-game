# Plano — Desfecho por andar da Torre [TORRE_DESFECHO]

> **Status:** ✅ IMPLEMENTADO (2026-06-22). Textos aprovados pelo dono; aftermath (vitória) + defeat
> (derrota) por andar fiados do `TowerFloors` até a UI; i18n PT completo; 662 testes verdes.
> Relacionado: `TowerFloors.java`, `TowerService.fight`, `TowerController`, `godot-client/ui/Tower.gd`, `docs/LORE.md`.

## Objetivo

Hoje cada andar tem **um único texto** (`atmosphere` = a "chegada no andar"). Esse mesmo texto é
reusado em 3 lugares: lobby (próximo andar), andar ativo, e — repetido — no card de **resultado**
depois da vitória. Ou seja: **não existe um desfecho próprio pós-vitória** — a lore de chegada
aparece de novo.

Este plano adiciona um **segundo texto por andar**: o **desfecho** (`aftermath`) — a consequência
narrativa de derrotar o(s) inimigo(s) daquele andar. Lore de chegada continua na entrada; o desfecho
aparece **só ao vencer**, no card de resultado.

## Design técnico

1. **`TowerFloors.FloorDef`** ganha o campo `aftermath` (String). `floor(...)` e `mvp(...)` recebem
   o desfecho como parâmetro. Texto canônico = **inglês** (regra de UI [IDIOMA]); PT vai no i18n.
2. **`TowerFloors.floorAftermath(floor)`** (espelha `floorAtmosphere`), via
   `messages.getOr("tower.floor.<n>.aftermath", def)`.
3. **`TowerService.fight()`** → `FightResult` ganha campo `aftermath`. Ao **vencer**, preenche com
   `floorAftermath(floor)`; o slot `atmosphere` do resultado pode ser descontinuado (ou manter vazio).
4. **`TowerController.fight`** expõe `aftermath` no JSON.
5. **`Tower.gd` `_render_result`**: usa `last_result.aftermath` como a nota narrativa (em vez do
   `atmosphere` repetido). Fallback atual ("Chefe derrotado! Suba...") só se vier vazio.
6. **i18n**: `tower.floor.<1..50>.aftermath` em `messages_pt.properties` (PT) + `messages.properties`
   (EN, opcional — o default já vem do `TowerFloors`).

### Casos especiais
- **Andar 50 (Rei Arka):** ao vencer dispara a **escolha do Arka** (poupar/matar), que já tem
  narrativa própria (`tower.arka.*`). O desfecho do 50 é o **golpe caindo / a luz se apagando**, que
  emenda direto na tela de escolha. (O resultado e a escolha aparecem empilhados — ver `_render`.)
- **Derrota:** mantém a nota atual ("☠ Derrotado — cure-se no Templo"). Desfecho é só de vitória.
  (Opcional futuro: texto de derrota por zona.)

### Princípios de escrita (RPG dark antigo — voz Souls/grimdark)
- 2ª pessoa, presente, 1–2 frases (MVP pode ter 2–3 + 1 fala).
- O desfecho **avança o terror**, não só "você venceu": a maré/sangue que sobe, a coisa que dorme
  embaixo, a luz emprestada do Rei, cada morto que enfim "descansa".
- **MVPs deixam um gancho** da próxima zona (uma fala/imagem).
- Coerência com a `atmosphere` de chegada do mesmo andar.

---

## Os 50 desfechos (rascunho PT)

### Zona 1 (1–9): Salões Baixos — a guarda caída · MVP 10: O Capitão Caído

1. **Sentinela do Portão** — A Sentinela tomba, e da fenda no chão o cheiro de maré sobe mais forte — como se algo lá embaixo respirasse aliviado. A marca redonda na pedra está úmida agora.
2. **Sentinela do Salão** — A lança quente esfria assim que a Sentinela cai. O que a aquecia já subiu os degraus à sua frente — você só não chegou a tempo de ver.
3. **Vigia Pálido** — Com o Vigia desfeito, sua sombra volta a cair para o lado certo — por um instante. As chamas pálidas se inclinam todas para a escada acima, como dedos apontando.
4. **Casca do Desertor** — Quando a Casca desaba, os dados sobre a mesa terminam de rolar sozinhos. Todos param no mesmo número.
5. **Casca do Vigia** — A coroa bordada no estandarte enfim se desfaz em pó negro. Fica só pano — como se nunca houvesse pertencido a rei nenhum.
6. **Mortos Rastejantes** — As marcas de mãos param de subir a parede. A última ainda está fresca, grande o bastante para a sua caber dentro dela.
7. **Sacerdote Comido pela Cera** — Ele se apaga junto com as velas. A cera que rastejava escada acima reflui, escorrendo de volta para a fenda lá embaixo — alimentando o que quer que more ali.
8. **Leais Até a Morte** — O último deles, ao cair, ainda ergue a mão numa saudação derradeira — a você, ou a algo logo atrás de você. Você não se vira para conferir.
9. **Quebra-Portões** — Ele cai com um estrondo que faz a torre tremer. Acima, o portão superior range e se abre — destrancado pelo lado de dentro, por mão nenhuma.
10. **[MVP] O Capitão Caído** — Sor Bramm Holt enfim abaixa a espada que segurou erguida por anos. Por um instante, antes do fim, os olhos dele são apenas os de um homem cansado — e gratos. "Mais alto", ele sopra, "a corte mente. Não escute."

### Zona 2 (11–19): A Corte — a nobreza podre · MVP 20: O Comido pelo Ouro

11. **Desgraçado Dourado** — Ele morre agarrado a um punhado de moedas que enferrujam entre os dedos antes mesmo de cair. O ouro aqui não compra nem a própria queda.
12. **Preso ao Espelho** — Em cada espelho, seu reflexo tomba um batimento depois dele. E então — um batimento atrasado — sorri, ainda que você não esteja sorrindo.
13. **Cortesão Sem Rosto** — Ele se dissolve, e nos retratos riscados todos os olhos que sobraram se voltam de uma vez para acompanhá-lo até a escada.
14. **Apodrecidos pelo Banquete** — A mesa enfim silencia. Os convivas tombam sobre os pratos, no meio de uma garfada — o banquete, afinal, terminou de comê-los.
15. **Cadáver Empoado** — Com ele, o perfume também morre. O que havia por baixo sobe sem máscara — doce, podre, grudado na sua garganta por toda a escada seguinte.
16. **Os Ajoelhados** — Soltos do que os mantinha de joelhos, despencam para a frente e ficam imóveis. Nenhum deles olhava para o trono — todos encaravam o chão.
17. **Guarda-Contas** — Os livros se abrem quando ele cai. O nome escrito mil vezes é o do Rei — e abaixo da última linha, em tinta ainda fresca, está o seu.
18. **Horror Incrustado de Joias** — As joias que brotavam das paredes escurecem todas de uma vez, cegas de novo. O salão volta a ser só pedra olhando para pedra.
19. **Duelista das Mentiras** — Ele cai diante de uma lâmina de verdade pela primeira e última vez. No rosto, algo quase como alívio — por enfim ter perdido um duelo que era real.
20. **[MVP] O Comido pelo Ouro** — Lorde Casnar Vane morre ainda dizendo um número, o preço subindo a cada fôlego, como se em algum lugar ainda houvesse quem pagasse. Quando se cala, a pedra sob a corte está morna, e os degraus à frente descem para um vermelho que escorre para cima.

### Zona 3 (21–29): As Profundezas do Ritual · MVP 30: O Eco Coroado

21. **Acólito Sangrante** — Seu sangue se junta ao vermelho que já escala os degraus e dispara escada acima. Todo o sangue desta torre tem pressa de chegar ao topo.
22. **Espectro de Incensário** — Os incensários param, frios. A fumaça de cobre e prece é puxada para baixo, engolida pela fenda — como um fôlego inspirado por algo grande demais, lá no fundo.
23. **Guardião do Círculo** — Ele se desfaz nos sulcos entalhados e some — só mais uma camada na ferida que este chão vem reabrindo há anos. Os sulcos parecem um pouco mais fundos agora.
24. **Os Cantantes** — O canto se rompe no meio de uma palavra. O silêncio que fica tem um formato — e o formato espera, paciente, que alguém o termine. Você sobe depressa, antes que seja você.
25. **Coração de Cristal** — O cristal que pulsava no seu ritmo racha e para. Por um fôlego, o seu também para — e quando volta a bater, bate um pouco mais devagar, como se tivesse aprendido algo.
26. **Vinhateiro de Sangue** — Ele tomba, e o frasco com a data de hoje se entorna. O sangue não escorre para o chão: sobe pela parede, ávido, para se juntar ao resto que já corre ao topo.
27. **O Rito Não-Nascido** — A coisa que ainda tentava nascer para de tentar. Você não sabe dizer se a matou ou se apenas a deixou, enfim, descansar. As duas ideias te assustam por igual.
28. **Coisa do Altar** — Desfeita ela, o sangue nas paredes hesita, para por um instante — e segue subindo mesmo assim. Já não precisa de servos: aprendeu o caminho sozinho.
29. **O Pretendente** — Ele despenca no trono de osso, enfim sem coroa, tendo ensaiado a vida toda uma coroação que nunca foi para ele. Lá em cima, alguém de verdade está prestes a ser coroado.
30. **[MVP] O Eco Coroado** — O Eco se desfia, e por um instante veste o luto do Rei em vez do rosto dele. "Você o verá em breve", diz, com a voz do Rei. "Diga a ele que eu tentei ser ele." Então a voz se apaga, e fica só o eco de um eco, subindo a escada.

### Zona 4 (31–39): A Sombra do Rei · MVP 40: O Xamã

31. **O Sem-Sombra** — Não deixa corpo, nem sombra sequer na morte. Você sobe — e só alguns degraus depois percebe que também parou de projetar a sua.
32. **Tomado pelo Fôlego** — As paredes expiram pela primeira vez e o soltam. O vento morno se inverte e passa a te empurrar para cima, ansioso, como quem entrega uma encomenda há muito esperada.
33. **Horror Dragado** — Ele larga a pedra e o ouro que vestia como pele e desliza de volta em direção à fenda — chamado para casa pelo que o dragou das profundezas. Você não o segue.
34. **Escriba Afogado em Tinta** — Ele morre de bruços sobre as anotações do Rei. A última linha legível, numa caligrafia mal humana, diz: *nunca foi uma torre. é uma garganta.* E você está subindo por dentro dela.
35. **O Vir-a-Ser** — As coisas meio derretidas se desfazem, e por um instante você vislumbra a forma única para a qual todas convergiam. Ela tem o rosto do Rei.
36. **Sussurro Meloso** — Ele morre no meio da promessa, depois de te oferecer tudo o que você mais quer. O silêncio que vem depois é a primeira coisa nesta torre que não mentiu. Você quase sente falta da voz.
37. **Vibração Feita Carne** — A carne para, mas a vibração não. Ela continua sem ela, lá no fundo, grave e constante — um batimento que nunca precisou de um corpo.
38. **Guarda do Limiar** — O último cai diante da soleira que não pôde cruzar, e a mão estendida aponta o caminho acima, para onde lhe foi proibido seguir o Rei. "Traga-o de volta", ele pede, "ou não traga nada."
39. **O Selo Falho** — O selo se rompe de vez. O que ele tentou manter trancado já se foi há muito — para cima, à sua frente, em direção ao Rei. A porta chamuscada se abre para uma escada que cheira a maré.
40. **[MVP] O Xamã** — Oren não tomba: se solta, sorrindo, escorregando de volta para o mar que o cuspiu. "Paz", repete, encantado, enquanto se desfaz. "Você vai dá-la a ele por mim." Acima do corpo que não fica, a torre deixa de ser torre — as escadas continuam, as paredes desistem.

### Zona 5 (41–49): O Limiar · MVP 50: Rei Arka

41. **Caminhante da Borda** — Ele dá um passo para fora de uma borda que não existe e some. As escadas continuam; você continua com elas, porque já não há mais nada além de subir. Para trás também deixou de existir.
42. **Coisa-Vertigem** — Quando morre, você cai — para cima, para baixo, impossível dizer — e aterrissa subindo, como se a escada o tivesse aparado e seguido em frente. A vibração nos seus dentes agora tem ritmo. É um coração.
43. **O Indeciso** — Morre antes de decidir o que seria — homem, fera, ou nada. Você lhe poupa a escolha sem querer; talvez seja a única misericórdia que esta torre permite. Lá em cima, alguém já escolheu por todos.
44. **Sonho do Adormecido** — O sonho estoura como um fôlego enfim solto. Lá no fundo, a coisa imensa para de se revirar e fica quieta — não a quietude de quem volta a dormir, mas a de quem, depois de muito tempo, decidiu acordar.
45. **A Maré Ávida** — A maré se parte em torno da coisa que tomba e segue em frente, escada acima, te deixando para trás. O sangue tem um lugar onde precisa estar, e está quase lá. Você apressa o passo para não chegar depois dele.
46. **Coroado de Cinzas** — Ele queima uma última vez e, desta vez, não é reconstruído. O trono de estandartes desaba em brasas que, é claro, sobem em vez de cair. Até o fogo conhece o caminho.
47. **A Escuridão que Escuta** — Você golpeia a escuridão e ela escuta isso também — e guarda. O silêncio que vem depois está mais cheio do que antes, por exatamente uma morte. Algo lá em cima ouviu você chegar.
48. **O Um Degrau Atrás** — Você derruba a coisa que vestia o seu rosto, e ela cai um degrau atrás de você, ainda sorrindo — como se não se importasse, como se fosse apenas tentar de novo mais acima. Você não olha para trás pelo resto da subida.
49. **O Limiar Choroso** — A porta se abre. O choro não cessa: está mais alto agora, logo além dela, e reconhece os seus passos. "Você demorou", diz a voz do Rei. "Que bom. Que bom."
50. **[MVP] Rei Arka** — A luz emprestada o abandona, e Arka volta a ser apenas um homem — de joelhos, a chave gasta, o reino que fundou todo contido num corpo que treme. Lá embaixo, o que a luz mantinha à espera se cala, atento. Tudo nesta torre esperou por este instante. Agora ele te olha, e abre a boca para implorar.

---

## Próximos passos (após aprovação dos textos)
1. Travar a versão **EN canônica** dos 50 (texto de UI = inglês).
2. Adicionar campo `aftermath` em `FloorDef` + `floor()/mvp()` + `floorAftermath()`.
3. `FightResult.aftermath` + controller + `Tower.gd _render_result`.
4. i18n `tower.floor.<n>.aftermath` (PT + EN).
5. Teste: `/fight` ao vencer retorna `aftermath` não-vazio; andar 50 emenda na escolha do Arka.
