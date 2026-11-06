# Galaxy Android

Fork de desenvolvimento baseado no host Android do [Nebula](https://github.com/keepvaibin/nebula),
que compila uma adaptação da decompilação [Petari](https://github.com/SMGCommunity/Petari)
como código ARM64 nativo. Preserva os créditos, o histórico e as licenças de ambos.
Não é uma publicação oficial desses projetos.

O Petari original reconstrói o programa Wii/PowerPC. A camada Nebula acrescenta
Vulkan, SDL3, áudio, importação de arquivos, armazenamento e compatibilidade ARM64.
O checkout Petari original permanece separado como referência.

## Controles

- Analógico esquerdo: movimento. A: salto/confirmar. Z: agachar, com as combinações
  originais de salto longo e salto mortal. Giro: sacudir sem precisar mover o telefone.
- Ponteiro: arraste sobre a imagem para apontar o Wii Remote e coletar Star Bits;
  B dispara. As telas de seleção continuam usando o ponteiro.
- Botão PONTEIRO/CÂMERA: alterna o destino do arrasto livre. Câmera envia os
  comandos direcionais originais do Wii; cada cenário permite ou limita os passos
  de câmera. Não é uma órbita livre criada por cima das cenas do jogo.
- Arrastar para a direita olha para a direita. O comando horizontal Wii é
  compensado para esse sentido, conferido dentro de Castle Gardens. Segure
  PONTEIRO/CÂMERA para ajustar sensibilidade e inversão X/Y; as escolhas persistem.
- C conserva a função original de câmera. + pausa; Home abre as opções do host.
- Movimento, ponteiro/câmera e ações podem usar dedos independentes. Cruzar regiões
  não transfere a função do dedo. Cancelamento, foco, resize e pausa liberam entrada.
- O arranjo pode ser reposicionado e salvo por formato de tela. Controles usam
  Canvas e vetores, sem acrescentar texturas ao renderizador do jogo.
- O arranjo padrão deixa livres LIFE, moedas e Star Bits. Posições personalizadas
  continuam sendo respeitadas.

## Compilação

Android 12/API 31+, ARM64 e Vulkan 1.1. JDK 17, SDK 36, NDK 30.0.16248370,
CMake 3.31.6/Ninja, Rust com alvo `aarch64-linux-android`.

As ferramentas host SPIR-V podem ser construídas sem Visual Studio, usando
[LLVM-MinGW e dependências verificadas por SHA256](../cmake/android_host_tools/README.md).
O build PowerShell aceita `-NdkVersion`, `-Jobs`, `-HostTools` e `-WithoutFsr3`.
Essa última opção deixa FSR 3 fora do build; o renderizador básico Vulkan e FSR 1
continuam disponíveis.

Exemplo a partir da raiz do repositório, com as ferramentas em `../.tools`:

```powershell
$env:JAVA_HOME = (Resolve-Path ../.tools/jdk17).Path
$env:ANDROID_HOME = (Resolve-Path ../.tools/android-sdk).Path
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/Temp'
# Python 3 com zlib, CMake/Ninja e cargo precisam estar no PATH.
# Em Windows GNU, configura Rust/NDK/LLVM-MinGW sem exigir Visual Studio:
. ./tools/importer_env.ps1
$Deps = (Resolve-Path ../.tools/galaxy-deps).Path
./platform/android/build_apk.ps1 -Deps $Deps -HostTools "$Deps/host-build" `
  -Work "$PWD/build/android-package" -Abis arm64-v8a -Jobs 4 -WithoutFsr3 `
  -Signing 'C:/private/galaxy-signing.properties'
```

O arquivo de assinatura externo contém `storeFile`, `storePassword`, `keyAlias` e
`keyPassword`; não faz parte do código. Sem `-Signing`, o script usa a chave de
depuração e esse APK não substitui o pacote assinado entregue. Não use
`-TestHooks` em uma distribuição. O build gera os programas SPIR-V previamente,
empacota quatro bibliotecas ARM64 e os textos públicos de licenças. Não inclui
arquivos do disco. A primeira compilação dos shaders pode ser demorada.

O pacote deste fork é `com.genisson.galaxy.android`; o namespace Java/JNI original
é mantido para preservar a integração. Não substitui o Futurama ou o Nebula original.

## Performance

Instalações novas começam com o perfil Qualidade: alvo de 60 FPS, FSR 1 a 1,5×,
HUD e textos na resolução da tela. Em Settings → Graphics, Qualidade e Nativo
podem ser escolhidos para a próxima abertura do jogo. A criação inicial não altera
um `config.ini` existente, inclusive vazio ou inválido.

No aparelho de teste, esse perfil passou de 49,52 para 59,83 FPS apresentados na
mesma cena inicial. A [validação da versão](ANDROID_VALIDATION_0.1.0.md) informa
resoluções, tempos, memória, condições e limites da comparação.

## Testes

```powershell
python platform/android/tests/run_touch_tests.py --jdk ../.tools/jdk17 --android-jar ../.tools/android-sdk/platforms/android-36/android.jar
python platform/android/tests/run_render_profile_tests.py --jdk ../.tools/jdk17
python tools/run_android_checks.py --serial DEVICE_SERIAL
python tools/verify_android_apk.py PATH_TO_APK
python -m unittest discover -s tools -p test_verify_android_apk.py
python tools/profile_android.py --adb ../.tools/platform-tools/adb.exe --seconds 45 --output artifacts/profile.json
```

[Cinco suítes nativas isoladas](../cmake/android_checks/README.md) cobrem entrada,
ponteiro, comandos, arquivos de save sintéticos e matemática ARM64/NEON.
O runner `dev.nebula.galaxy.TouchControlsInstrumentation` usa MotionEvents reais
no View, sem chamar JNI ou usar dados comerciais.

Para executar a instrumentação em um pacote isolado:

```powershell
# No diretório platform/android, passe também os caminhos SDL/assets do seu build:
./gradlew.bat assembleDebug assembleDebugAndroidTest `
  -PgalaxyApplicationId=com.genisson.galaxy.android.validation `
  -Pnebula.sdlDir=PATH_TO_SDL3 -Pnebula.assetsDir=PATH_TO_LICENSE_ASSETS
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.genisson.galaxy.android.validation.test/dev.nebula.galaxy.TouchControlsInstrumentation
```

O importador também tem regressões Rust de prepass BRK, paridade de shaders,
cancelamento e preservação da saída anterior. No ambiente Windows GNU:

```powershell
. ./tools/importer_env.ps1
cargo test --release --locked -p nebula-import --target x86_64-pc-windows-gnu -j 3
```

## Dados e validação

Cada jogador fornece sua imagem RVZ/ISO/WBFS compatível. O importador verifica o
ID/revisão e o SHA-1 de `main.dol`, transforma os arquivos em `game.pak` e mantém
conteúdo e saves nos dados privados do aplicativo. Nenhuma imagem, conteúdo
extraído ou save faz parte do repositório ou do APK.

As correções incluem o prepass de animações BRK antes de converter modelos
(evita IDs de pixel shader ausentes), o uso do alinhamento SSBO informado pela GPU,
e a substituição atômica do pacote convertido sem apagar a instalação anterior
antes de uma conversão bem-sucedida. Saves ficam separados do pacote de jogo.

No aparelho, foram verificados importação da revisão USA suportada, criação de
slot, menus e gameplay em Castle Gardens. Dois arquivos de save gerados nesse
teste permaneceram idênticos durante a reimportação corrigida. Isso não valida
toda a campanha. Consulte o relatório da versão para os resultados e limites.
