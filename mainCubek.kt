package Cubeki

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf

import kotlinx.coroutines.launch                    // запуск корутин
import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            // только для чтения
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest

enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean,
    val sawPlayerNearSource: Boolean = false,
    val isStopped: Boolean = false,
    val posX: Float = 3f,
    val posZ: Float = 3f
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val hp: Int,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String,
    val gold: Int
)

fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{

    val dx = ax - bx
    val dz = az - bz
    return kotlin.math.sqrt(dx*dx + dz*dz)
}

fun initialPlayerState(playerId: String): PlayerState {
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            100,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            100,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcId: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView{
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet) {
                    "Привет, ты кто"
                }else{
                    "Ну что ${player.playerId} я жду?!"
                }
            DialogueView(
                "Алхимик",
                "$greeting\nТащи траву",
                listOf(
                    DialogueOption("accept_help", "Акей"),
                    DialogueOption("threat", "Нит, ты давай")
                )
            )
        }
        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView(
                    "Алхимик",
                    "Мало, мне надо 4 вщто",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "спс",
                    listOf(
                        DialogueOption("give_herb", "Отдать 4 травы")
                    )
                )
            }
        }
        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Ну что, похимичим?!"
                }else{
                    "Ты завершил квест, но память не обновилась, капут"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }
        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "я с тобой больше не дружу",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand{
    val playerId: String
}
data class CmdTakeDamage(
    override val playerId: String,
    val hp: Int,
    val damage: Int
): GameCommand

data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayer: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

data class CmdSpawnCube(
    override val playerId: String
) : GameCommand
data class CmdResetCubes(
    override val playerId: String
) : GameCommand

sealed interface GameEvent{
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent


data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent
data class CubeSpawned(
    val x: Float,
    val y: Float,
    val z: Float,
    override val playerId: String
) : GameEvent
data class CubeDel(
    override val playerId: String
) : GameEvent

class GameServer {
    val worldObjects = mutableListOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        )
    )
    private val spawnedCubes = mutableListOf<Pair<Float, Float>>()
    private val _cubeCount = MutableStateFlow(0)
    val cubeCount: StateFlow<Int> = _cubeCount.asStateFlow()

    private  val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _command = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _command.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _command.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope){
        scope.launch {
            commands.collect{cmd ->
                processCommand(cmd)
            }
        }
    }
    private fun setPlayerState(playerId: String, data: PlayerState){
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }
    fun getPlayerState(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->  //> minBy = берёт ближайший объект по расстоянию до игрока | OrNull - если нет таких объектов - вернуть null
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerState(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId) {
            val newHint =
                when (newAreaId) {
                    "alchemist" -> "Подойди к алхимику"
                    "herb_source" -> "Собери траву"
                    else -> "Подойди к одной из локаций"
                }
            updatePlayer(playerId) {p -> p.copy(hintText = newHint)}
            return
        }

        if(oldAreaId != null) {
            _events.emit(LeftArea(playerId, oldAreaId)) //> emit - "Сообщи всем подписчикам, что произошло событие LeftArea"
        }

        if (newAreaId != null) {
            _events.emit(EnteredArea(playerId, newAreaId))

            if (newAreaId == "herb_source") {
                updatePlayer(playerId) { p ->
                    val memor = p.alchemistMemory
                    if (!memor.sawPlayerNearSource) {
                        p.copy(alchemistMemory = memor.copy(sawPlayerNearSource = true))
                    }else p
                }
            }

        }

        val newHint =
            when (newAreaId) {
                "alchemist" -> "Подойди к алхимику"
                "herb_source" -> "Собери траву"
                else -> "---"
            }
        updatePlayer(playerId) {p -> p.copy(hintText = newHint, currentAreaId = newAreaId)}
    }

    private suspend fun processCommand(cmd: GameCommand) {
        when (cmd) {
            is CmdTakeDamage -> {
                updatePlayer(cmd.playerId) { p ->
                    val newHp = (p.hp - cmd.damage).coerceAtLeast(0)
                    val newPlayer = p.copy(hp = newHp)


                    _events.tryEmit(ServerMessage(cmd.playerId, "Получено ${cmd.damage} урона | HP: ${newHp}"))


                    if (newHp <= 0) {
                        _events.tryEmit(ServerMessage(cmd.playerId, "you died"))
                    }
                    newPlayer
                }
            }
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId) { p -> p.copy(posX = p.posX + cmd.dx, posZ = p.posZ + cmd.dz)
                }
                refreshPlayerArea(cmd.playerId)
            }

            is CmdInteract -> {
                val player = getPlayerState(cmd.playerId)
                val obj = nearestObject(player)
                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом пусто"))
                    return
                }
                val dist = distance2d(player.posX, player.posZ, obj.x, obj.z)
                val herb = herbCount(player)


                if (dist > obj.interactRadius) {
                    _events.emit(ServerMessage(cmd.playerId, "аэ ты куда"))
                    return
                }

                when (obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        if(herb < 3 && newMemory.sawPlayerNearSource){
                            DialogueView(
                                "Алхимик",
                                "а я тебя видел на herb source",
                                emptyList()
                            )
                        }


                        updatePlayer(cmd.playerId) {p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Трава тебе не надо щас, сначала квест"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId){ p->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))

                    }

                }
            }
            is CmdChooseDialogueOption -> {
                val player = getPlayerState(cmd.playerId)

                if (player.currentAreaId != "alchemist") {
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к алхимику"))
                    return
                }

                when (cmd.optionId) {
                    "accept_help" -> {
                        if (player.questState != QuestState.START) {
                            _events.emit(ServerMessage(cmd.playerId, "Путь помощи нельзя смениьт"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик попросил собрать 3 травы"))
                    }

                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                            return
                        }

                        val herbs = herbCount(player)


                        if (herbs < 3) {
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы. Нужно 3, у тебя $herbs"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory = if (newCount <= 0) {
                            player.inventory - "herb"
                        } else {
                            player.inventory + ("herb" to newCount)
                        }

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(
                                inventory = newInventory,
                                gold = p.gold + 5,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }
                        
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал тебе золото"))
                        _events.emit(ServerMessage(cmd.playerId, "Сундук появился"))
                    }

                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестно"))
                    }
                }
            }
            is CmdSwitchActivePlayer -> {
                //
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) {_ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен"))
            }
            is CmdSpawnCube -> {
                val player = getPlayerState(cmd.playerId)
                val genX = 2f
                val genZ = 2f
                val interactRadius = 2f

                if (distance2d(player.posX, player.posZ, genX, genZ) <= interactRadius) {
                    val count = spawnedCubes.size + 1
                    // Куб размером 1x1x1. Y=0.5f ставит его на землю, каждый следующий +1.0f
                    val newY = 0.5f + (count - 1) * 1.0f

                    spawnedCubes.add(genX to genZ)
                    _cubeCount.value = spawnedCubes.size// просто для подсчёта
                    _events.emit(CubeSpawned(genX, newY, genZ, cmd.playerId))
                    _events.emit(ServerMessage(cmd.playerId, "башня увеличилась"))
                } else {
                    _events.emit(ServerMessage(cmd.playerId, "ближе нажо"))
                }
            }
            is CmdResetCubes -> {
                val count = spawnedCubes.size
                spawnedCubes.clear()
                _cubeCount.value = 0 // Сбрасываем счётчик
                _events.emit(CubeDel(cmd.playerId))
                _events.emit(ServerMessage(cmd.playerId, "Все кубы удалены"))
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")
    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))
    val log = mutableStateOf<List<String>>(emptyList())
    val cubeCount = mutableStateOf(0)
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState): String{
    return if (player.inventory.isEmpty()){
        "Inventory: (пусто)"
    }else{
        "Inventory:" + player.inventory.entries.joinToString { "${it.key}: ${it.value}" }
    }
}

fun currentObjective(player: PlayerState): String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 3 травы. сейчас $herbs /3"
            else "Вернись к алхимику и отдай 3 травы"
        }
        QuestState.GOOD_END -> "Квест завершён хорошо"
        QuestState.EVIL_END -> "Квест завершён плохо"
    }
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is EnteredArea -> "EnteredArea: ${e.areaId}"
        is LeftArea -> "LeftArea: ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc: ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource: ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился:${e.memory.hasMet} | Сколько раз поговорил: ${e.memory.timesTalked} | отдал траву: ${e.memory.receivedHerb}"
        is ServerMessage -> "Server: ${e.text}"
        is CubeSpawned -> "CubeSpawned: x=${"%.1f".format(e.x)}, y=${"%.1f".format(e.y)}, z=${"%.1f".format(e.z)}"
        is CubeDel -> "удалено"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        herbNode.transform.translate(3f, 0f, 0f)

        val chestNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        chestNode.transform.translate(1f, 0f, 0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, 1f))
            setColor(Color.YELLOW, 5f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerState(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        alchemistNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        herbNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        chestNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        val spawnedCubeNodes = mutableListOf<de.fabmax.kool.scene.Node>()


        coroutineScope.launch {
            server.events.filter { it is CubeSpawned }.collect { evt ->
                val spawn = evt as CubeSpawned
                val newCube = addColorMesh {
                    generate { cube { colored() } }
                    shader = KslPbrShader {
                        color { vertexColor() }
                        metallic(0f)
                        roughness(0.25f)
                    }
                }
                newCube.transform.translate(spawn.x, spawn.y, spawn.z)
                spawnedCubeNodes.add(newCube)
            }
        }

        coroutineScope.launch {
            server.events.filter { it is CubeDel }.collect {
                spawnedCubeNodes.forEach { node ->
                    node.parent?.also { parent ->

                    }

                    node.isVisible = false
                }
                spawnedCubeNodes.clear()
            }
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map { map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
            .onEach { player ->
                hud.playerSnapShot.value = player
            }
            .launchIn(coroutineScope)
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map { event ->
                eventToText(event)
            }
            .onEach { line ->
                hudLog(hud, "[${hud.activePlayerIdFlow.value}] $line")
            }
            .launchIn(coroutineScope)
        coroutineScope.launch {
            server.cubeCount.collect { hud.cubeCount.value = it }
        }
        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), (12.dp)))
                .padding(12.dp)
            Column {
                val player = hud.playerSnapShot.use()
                val dialogue = buildAlchemistDialogue(player)

                Text("Игрок: ${hud.activePlayerIdUi.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Позиция x=${"%.1f".format(player.posX)} z=${"%.1f".format(player.posZ)}") {}
                Text("Quest State: ${player.questState}") {
                    modifier.font(sizes.smallText)
                }
                Text(currentObjective(player)) {
                    modifier.font(sizes.smallText)
                }
                Text(formatInventory(player)) {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }
                Text("Кубэки: ${server.cubeCount.value}") {
                    modifier.margin(top = sizes.smallGap, bottom = sizes.smallGap)
                }

                Row {
                    Button("Сменить") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdFlow.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }

                    Button("Сбросить") {
                        modifier.onClick {
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }
                Text("Движение:") { modifier.margin(top = sizes.gap) }

                Row {
                    modifier.margin(bottom = sizes.smallGap)


                    val hpPercent = player.hp.coerceIn(0, 100)
                    val hpColor =
                        if (hpPercent > 60) Color(0.1f, 0.75f, 0.25f, 0.9f)
                        else if (hpPercent > 30) Color(0.9f, 0.7f, 0.15f, 0.9f)
                        else Color(0.9f, 0.15f, 0.1f, 0.9f)
                    if (player.hp == 0) {
                        addPanelSurface {
                            modifier
                                .align(AlignmentX.Center, AlignmentY.Center)
                                .background(RoundRectBackground(Color(0f, 0f, 255f, 0.6f), (12.dp)))
                                .padding(24.dp)
                            Text("Ты сдох :(") {
                                modifier
                                    .font(sizes.largeText)
                                    .margin(end = 8.dp)
                                    .align(AlignmentX.Center, AlignmentY.Center)

                            }
                        }
                    }
                    Text("HP: $hpPercent%") {
                        modifier.font(sizes.smallText).margin(end = 8.dp)
                    }

                    Box {
                        modifier
                            .width(120.dp)
                            .height(12.dp)
                            .background(RoundRectBackground(Color(0.05f, 0.05f, 0.05f, 0.7f), 6.dp))

                        Box {
                            modifier
                                .width((hpPercent * 120 / 100).dp)
                                .height(12.dp)
                                .background(RoundRectBackground(hpColor, 6.dp))
                        }
                    }

                    Row {
                        Button("Лево") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdMovePlayer(player.playerId, dx = -0.5f, dz = 0f))
                            }
                        }
                        Button("Право") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdMovePlayer(player.playerId, dx = 0.5f, dz = 0f))
                            }
                        }
                        Button("Вперёд") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = -0.5f))
                            }
                        }
                        Button("Назад") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = 0.5f))
                            }
                        }
                    }

                    Text("Взаимодействия") { modifier.margin(top = sizes.gap) }
                    Row {
                        Button("Потрогать ближайшего") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdInteract(player.playerId))
                            }
                        }
                        Button("Ударить себя (10)") {
                            modifier.onClick {
                                server.trySend(CmdTakeDamage(player.playerId, hp = player.hp, damage = 10))
                            }
                        }
                        Button("Создать куб  ") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdSpawnCube(player.playerId))
                            }
                        }
                        Button("Сбросить кубы 🧹") {
                            modifier.margin(end = 8.dp).onClick {
                                server.trySend(CmdResetCubes(player.playerId))
                            }
                        }

                        Text(dialogue.npcId) { modifier.margin(top = sizes.gap) }

                        Text(dialogue.text) { modifier.margin(bottom = sizes.smallGap) }

                        if (dialogue.options.isEmpty()) {
                            Text("Нет доступных вариантов ответа") {
                                modifier.margin(top = sizes.gap).font(sizes.smallText).margin(bottom = sizes.gap)
                            }
                        } else {
                            Row {
                                for (option in dialogue.options) {
                                    Button(option.text) {
                                        server.trySend(
                                            CmdChooseDialogueOption(player.playerId, option.id)
                                        )
                                    }
                                }
                            }
                        }
                        Text("лог: ") { modifier.margin(top = sizes.gap, bottom = sizes.gap) }

                        for (line in hud.log.use()) {
                            Text(line) { modifier.font(sizes.smallText) }
                        }
                    }
                }
            }
        }
    }
}
