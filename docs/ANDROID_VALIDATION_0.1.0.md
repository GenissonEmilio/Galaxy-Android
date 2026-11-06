# Galaxy Android 0.1.0-dev — validação

Data: 6 de novembro de 2026. Fork de desenvolvimento Android de Nebula/Petari,
compilado em ARM64 nativo com Vulkan e SDL3. Esta versão não constitui validação
de toda a campanha nem uma publicação oficial dos projetos de origem.

## Pacote entregue

- `Galaxy-Android-0.1.0-dev-arm64.apk`, 36.255.383 bytes, versionCode 1.
- ID: `com.genisson.galaxy.android`. Android 12/API 31+, ARM64, Vulkan 1.1.
- SHA-256: `32acafdcff0f57398a7c9d8aead37d4613a1c46e4e65f6cc3c2a70ce94ed4050`.
- Certificado SHA-256: `5c2ee6aace747e4506982a1e97dac82d888fc8f9fbb0c607f29254e2d3ea5243`.
- Assinatura v3 e alinhamento ZIP de 16 KiB verificados pelo SDK. As quatro
  bibliotecas têm segmentos ELF alinhados a 16 KiB. Manifest final sem `debuggable`.
- Instalado com sucesso como atualização da instalação anterior de teste deste
  fork, com a mesma chave. A importação e o slot Mario 1 de teste permaneceram
  disponíveis, sem repetir a conversão dos dados.

A chave privada permanece fora do repositório. O pacote QA de instrumentação usa
outro ID; os testes não substituíram os dados do aplicativo entregue.

## Correções verificadas

- Importação direta e migração fazem o mesmo prepass BRK antes dos modelos.
  O contexto de ranges de animação é local a cada conversão, evitando reutilização
  de um estado global antigo. Isso elimina IDs de pixel shader que não existiam
  na tabela compilada. No aparelho, planetas e o modelo completo de Mario voltaram
  a aparecer; o log da execução corrigida deixou de reportar esses programas ausentes.
- Tabelas de matrizes de instâncias usam o alinhamento SSBO exigido pela GPU:
  64 bytes nesse Adreno, com testes também para 16 e 256. A reserva do upload ring
  contabiliza o padding entre tabelas e os índices continuam válidos.
- A instalação anterior permanece disponível durante uma nova conversão. Falha
  ou cancelamento não apagam o pacote anterior. Saves usam diretório separado.
- Cada dedo conserva sua função: movimento, ponteiro/câmera ou botão. IDs esparsos,
  cruzamento de regiões, resize, perda de foco e cancelamento têm regressões.
- Arrasto horizontal compensa o sentido dos comandos de câmera Wii. O ponteiro
  continua independente, e o D-pad direto conserva sua direção. Sensibilidade e
  inversão X/Y persistem. A janela de ajustes pausa o jogo.
- Defaults do HUD liberam LIFE e contadores de moedas/Star Bits; layouts salvos
  pelo jogador permanecem válidos. Dispositivos virtuais/internos do telefone não
  são tratados como controle externo. Cursor de canal desconectado não é desenhado.

## Testes de código

| Validação | Resultado | Alcance |
|---|---|---|
| Java no host | 78 assertions passaram | Geometria, posse dos dedos, direção e persistência de câmera |
| Instrumentação Android | 7 casos, 172 checks passaram | View real e MotionEvents, com listener que substitui JNI |
| Rust GNU Release | 68 testes passaram | Importação, BRK, paridade de modelos/shaders, cancelamento e publicação de saída |
| Executáveis ARM64 no telefone | 5 suítes passaram | Entrada nativa, projeção do ponteiro, controles, saves sintéticos e matemática NEON |
| Upload ring no host | CTest passou | Alinhamento real do alocador em 3 slots, 16/64/256 bytes |
| Verificador de APK | 5 testes unitários passaram | Recursos de ícone, shrink de nomes e rejeição de manifest de depuração |
| Perfil inicial Android | Teste JDK passou | Valores do perfil, configuração existente intacta e inicialização concorrente com 8 threads |

A projeção nativa do ponteiro verificou 1.991.428 amostras. O módulo real do jogo,
host Vulkan/SDL3, importador Android e frontend Gradle foram compilados em Release.
Os testes não substituem a validação de gameplay. A antiga suíte de threads não
foi contabilizada: o runner upstream referencia arquivos ausentes e não fornece
os serviços atuais do host; não foram introduzidos stubs para fazê-la passar.

## Aparelho e dados

POCO M8 Pro 5G, Adreno 810, Android API 36, tela 2772×1280 em paisagem,
atualização física de 120 Hz, depuração USB conectada. A simulação é limitada
a 60 FPS; o contador de submissões do host pode crescer duas vezes por frame,
pois contabiliza renderização e o pedido posterior de apresentação.

A imagem fornecida localmente foi identificada como RMGE01, USA revisão 0, com
`main.dol` SHA-1 `9a71008ae1ee9010e267fa67d1f0b0d4f0e895dd`, uma revisão suportada.
A conversão RVZ foi executada no próprio Android. Foram criados um slot e dois
arquivos de save de teste. Seus SHA-256 permaneceram idênticos após a conversão
corrigida; nenhum save preexistente foi apagado.

Foram observados seleção de arquivo, início da história, cenário completo de
Castle Gardens, movimento de Mario, salto, posição do ponteiro e passos de câmera.
Abrir ajustes de câmera, ir para a tela inicial, retornar e fechar a janela
retomou o jogo. A janela permanece pausada enquanto aberta.

O APK final também abriu Castle Gardens, executou movimento e salto e passou pela
conferência horizontal: direita olha à direita e esquerda retorna ao enquadramento
anterior. O HUD final deixou LIFE e os contadores livres na tela do aparelho.
As opções Qualidade/Nativo do launcher foram abertas e selecionadas. Os pacotes
QA e a cópia RVZ temporária enviada ao telefone foram removidos após a validação;
o arquivo ZIP original no computador e os dados do jogo instalado foram mantidos.

## Comparação de performance

Medições de 45 segundos com `tools/profile_android.py`, pela coluna de apresentação
real do SurfaceFlinger, com a cena de Castle Gardens estabilizada. O benchmark
compara perfis na mesma sessão de Release nativo, em uma embalagem temporária que
permitia ler os logs. Houve uma breve mudança de câmera no final da amostra nativa.
Não foram capturadas imagens durante as amostras.

| Perfil | Cena 3D | FPS apresentados | Intervalo p95 | Intervalos >25 ms | PSS do processo |
|---|---:|---:|---:|---:|---:|
| Nativo | 2772×1280 | 49,52 | 33,303 ms | 13,11% | 556,1–563,9 MiB |
| Qualidade, FSR 1, HUD nativo | 1848×853 | 59,83 | 16,651 ms | 0,30% | 539,1–546,0 MiB |

Na conferência adicional de 45 segundos do APK final, sem `debuggable`, depois de
alguns passos e testes de câmera na mesma área, foram medidos **56,31 FPS**,
intervalo p95 de **24,977 ms**, p99 de **33,303 ms**, 1,97% dos intervalos acima de
25 ms e PSS de **512,3–516,9 MiB**. Não é uma repetição controlada da comparação
acima: o processo foi reiniciado, o enquadramento mudou um pouco e não se controlou
a temperatura do aparelho. O alvo continua sendo 60 FPS; o resultado final mostra
que ainda há oscilação e não comprova 60 FPS constantes em toda a campanha.

O perfil Qualidade renderiza aproximadamente 44% dos pixels 3D da resolução
nativa. HUD e textos permanecem em resolução de saída. Não usa frame generation.
A melhoria medida vale para essa cena e essas condições; temperatura, bateria,
outros cenários e outros aparelhos podem alterar os resultados.

## Limites

- A câmera por arrasto envia os passos originais do D-pad. Respeita os bloqueios
  de cada cenário; não cria órbita analógica irrestrita nem remove câmeras roteirizadas.
- Não foram concluídas a campanha, todas as fases, habilidades, chefes ou estrelas.
  O giro está ligado à entrada original de sacudir; sua ação depende do progresso
  que libera essa habilidade. Esses testes não demonstram o seu uso em todas as fases.
- Não foi feita avaliação auditiva da mixagem nem teste de todos os controles físicos,
  sensores e diferentes modelos de telefone.
- FSR 3 está fora desta compilação. Vulkan/FSR 1 continuam disponíveis. O alvo é
  Android 12/API 31+, ARM64 e Vulkan 1.1.
- APK e repositório não contêm imagem de disco, arquivos extraídos, `game.pak`,
  saves, chaves de assinatura ou logs pessoais. Cada instalação precisa importar
  uma imagem compatível fornecida pelo jogador.

## Reproduzir

O [guia Android](ANDROID_PORT.md) descreve compilação e comandos de testes.
As dependências host têm URLs e hashes fixados no provisionador; as ferramentas
e os dados privados ficam fora do Git. A auditoria de conteúdo deve passar antes
de qualquer publicação de código.
