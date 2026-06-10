Trabalhe no arquivo godot-client/Battle.gd (Godot 4, replay 3D cosmético de batalha).

PROBLEMA: a animação de kiting do arqueiro (Hero) está estranha. Hoje, em _process()
nas linhas ~177-197, quando o arqueiro recua ele VIRA AS COSTAS pro goblin e corre
(gira pra direção da fuga + toca A_WALK pra frente), e a cada tiro gira de volta pra
mirar. Como ele é mais rápido que o goblin, fica alternando idle/passinhos curtos e o
personagem fica girando de um lado pro outro de forma nervosa. Parece fuga, não kiting.

COMPORTAMENTO DESEJADO (kiting clássico):
- O arqueiro ENCARA o goblin o tempo TODO (mira constante), nunca vira as costas.
- Quando precisa manter distância, ele ANDA PARA TRÁS (backpedal) ainda de frente pro
  goblin. Como o rig Quaternius não tem animação de andar pra trás, toque A_WALK
  INVERTIDO (play_backwards ou speed_scale negativo no AnimationPlayer) pra dar o passo
  de recuo correto.
- Quando para de recuar (já está na distância GAP), volta pro A_IDLE, ainda encarando.
- O tiro (A_SHOOT) acontece naturalmente já de frente pro goblin — não precisa girar.
- O "cruzar na borda" (_archer_cross com A_ROLL) é o ÚNICO momento em que ele reposiciona
  de lado; mantenha esse comportamento.

RESTRIÇÕES:
- É só cosmético/mock; não mexa em backend nem na lógica de dano/HP.
- Animações disponíveis (constantes no topo do arquivo): A_IDLE, A_HIT, A_SHOOT, A_WALK,
  A_JUMP, A_ROLL, A_DEATH. Não há animação de andar pra trás — use A_WALK invertido.
- Cuide das transições pra não piscar entre estados (idle <-> backpedal <-> shoot).
- Teste a lógica de rotação: a rotação Y deve sempre apontar pro goblin enquanto recua.

Comece relendo _process() e _archer_cross(), proponha a correção e aplique.
