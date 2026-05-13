package dupecube

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.platform.glfw.GlfwWindowSubsystem.input
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.sqrt
import java.io.File

enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType {
    ALCHEMIST,
    HERB_SOURCE,
    CHEST
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float = 1.7f
)

data class NpcMemory(
    val hasMet: Boolean = false,
    val timesTalked: Int = 0,
    val receivedHerb: Boolean = false,
    val sawPlayerNearSource: Boolean = false
)

data class PlayerState(
    val playerId: String,
    val posX: Float = 0f,
    val posZ: Float = 0f,
    val hp: Int = 100,
    val questState: QuestState = QuestState.START,
    val inventory: Map<String, Int> = emptyMap(),
    val alchemistMemory: NpcMemory = NpcMemory(),
    val currentAreaId: String? = null,
    val hintText: String = "Подойди к одной из локаций",
    val gold: Int = 0
)

fun herbCount(player: PlayerState): Int = player.inventory["herb"] ?: 0

fun distance2d(x1: Float, z1: Float, x2: Float, z2: Float): Float {
    val dx = x1 - x2
    val dz = z1 - z2
    return sqrt(dx * dx + dz * dz)
}

fun initialPlayerState(playerId: String): PlayerState = when (playerId) {
    "Stas" -> PlayerState(
        playerId = "Stas",
        hp = 100,
        alchemistMemory = NpcMemory(hasMet = true, timesTalked = 2),
        hintText = "Подойди к одной из локаций"
    )
    else -> PlayerState(playerId = playerId)
}

data class DialogueOption(val id: String, val text: String)
data class DialogueView(val npcId: String, val text: String, val options: List<DialogueOption>)

fun buildAlchemistDialogue(player: PlayerState): DialogueView {
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when (player.questState) {
        QuestState.START -> DialogueView(
            npcId = "Алхимик",
            text = if (!memory.hasMet) "Привет, ты кто?" else "Ну что, ${player.playerId}, я жду траву!",
            options = listOf(
                DialogueOption("accept_help", "Хорошо, помогу"),
                DialogueOption("threat", "Нет, сам давай")
            )
        )

        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView("Алхимик", "Маловато. Нужно хотя бы 3 травы.", emptyList())
            } else {
                DialogueView("Алхимик", "Отлично! Давай сюда травы.", listOf(
                    DialogueOption("give_herb", "Отдать 3 травы")
                ))
            }
        }

        QuestState.GOOD_END -> DialogueView(
            npcId = "Алхимик",
            text = if (memory.receivedHerb) "Ну что, похимичим?" else "Квест завершён, но что-то пошло не так...",
            options = emptyList()
        )

        QuestState.EVIL_END -> DialogueView(
            npcId = "Алхимик",
            text = "Я с тобой больше не дружу.",
            options = emptyList()
        )
    }
}

sealed interface GameCommand {
    val playerId: String
}

data class CmdTakeDamage(
    override val playerId: String,
    val damage: Int
) : GameCommand
data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
) : GameCommand
data class CmdInteract(
    override val playerId: String
) : GameCommand
data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
) : GameCommand
data class CmdResetPlayer(
    override val playerId: String
) : GameCommand
data class CmdSpawnCube(
    override val playerId: String
) : GameCommand
data class CmdResetCubes(
    override val playerId: String
) : GameCommand

sealed interface GameEvent {
    val playerId: String
}

data class EnteredArea(override val playerId: String, val areaId: String) : GameEvent
data class LeftArea(override val playerId: String, val areaId: String) : GameEvent
data class InteractedWithNpc(override val playerId: String, val npcId: String) : GameEvent
data class InteractedWithHerbSource(override val playerId: String, val sourceId: String) : GameEvent
data class InventoryChanged(override val playerId: String, val itemId: String, val newCount: Int) : GameEvent
data class QuestStateChanged(override val playerId: String, val newState: QuestState) : GameEvent
data class NpcMemoryChanged(override val playerId: String, val memory: NpcMemory) : GameEvent
data class ServerMessage(override val playerId: String, val text: String) : GameEvent
data class CubeSpawned(val x: Float, val y: Float, val z: Float, override val playerId: String) : GameEvent
data class CubesReset(override val playerId: String) : GameEvent

class GameServer {
    private val worldObjects = mutableListOf(
        WorldObjectDef("alchemist", WorldObjectType.ALCHEMIST, -3f, 0f),
        WorldObjectDef("herb_source", WorldObjectType.HERB_SOURCE, 3f, 0f)
    )

    private val spawnedCubes = mutableListOf<Pair<Float, Float>>()
    val cubeCount = MutableStateFlow(0)

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            _command.collect { processCommand(it) }
        }
    }

    private val _command = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)

    fun sendCommand(cmd: GameCommand) = _command.tryEmit(cmd)

    private fun updatePlayer(playerId: String, update: (PlayerState) -> PlayerState) {
        _players.update { map ->
            val old = map[playerId] ?: return@update map
            map + (playerId to update(old))
        }
    }
}
