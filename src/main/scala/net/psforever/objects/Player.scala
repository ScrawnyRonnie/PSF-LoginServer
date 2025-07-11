// Copyright (c) 2017 PSForever
package net.psforever.objects

import net.psforever.objects.avatar.interaction.{TriggerOnPlayerRule, WithEntrance, WithGantry, WithLava, WithWater}
import net.psforever.objects.avatar.{Avatar, LoadoutManager, SpecialCarry}
import net.psforever.objects.ballistics.InteractWithRadiationClouds
import net.psforever.objects.ce.{Deployable, InteractWithMines, InteractWithTurrets}
import net.psforever.objects.definition.{AvatarDefinition, ExoSuitDefinition, SpecialExoSuitDefinition}
import net.psforever.objects.equipment.{Equipment, EquipmentSize, EquipmentSlot, JammableUnit}
import net.psforever.objects.inventory.{Container, GridInventory, InventoryItem}
import net.psforever.objects.serverobject.{PlanetSideServerObject, environment}
import net.psforever.objects.serverobject.affinity.FactionAffinity
import net.psforever.objects.serverobject.aura.AuraContainer
import net.psforever.objects.serverobject.environment.interaction.common.{WithDeath, WithMovementTrigger}
import net.psforever.objects.serverobject.interior.InteriorAwareFromInteraction
import net.psforever.objects.serverobject.mount.MountableEntity
import net.psforever.objects.vital.resistance.ResistanceProfile
import net.psforever.objects.vital.{HealFromEquipment, InGameActivity, RepairFromEquipment, Vitality}
import net.psforever.objects.vital.damage.DamageProfile
import net.psforever.objects.vital.interaction.DamageInteraction
import net.psforever.objects.vital.resolution.DamageResistanceModel
import net.psforever.objects.zones.blockmap.BlockMapEntity
import net.psforever.objects.zones.{InteractsWithZone, ZoneAware, Zoning}
import net.psforever.types._

import scala.annotation.tailrec
import scala.util.{Success, Try}

class Player(var avatar: Avatar)
    extends PlanetSideServerObject
    with BlockMapEntity
    with InteractsWithZone
    with FactionAffinity
    with Vitality
    with ResistanceProfile
    with Container
    with JammableUnit
    with ZoneAware
    with InteriorAwareFromInteraction
    with AuraContainer
    with MountableEntity {
  interaction(environment.interaction.InteractWithEnvironment(Seq(
    new WithEntrance(),
    new WithWater(avatar.name),
    new WithLava(),
    new WithDeath(),
    new WithGantry(avatar.name),
    new WithMovementTrigger()
  )))
  interaction(new InteractWithMines(range = 10, TriggerOnPlayerRule))
  interaction(new InteractWithTurrets())
  interaction(new InteractWithRadiationClouds(range = 10f, Some(this)))

  private var backpack: Boolean = false
  private var released: Boolean = false
  private var armor: Int        = 0

  private var capacitor: Float                         = 0f
  private var capacitorState: CapacitorStateType.Value = CapacitorStateType.Idle
  private var capacitorLastUsedMillis: Long            = 0
  private var capacitorLastChargedMillis: Long         = 0

  private var exosuit: ExoSuitDefinition             = GlobalDefinitions.Standard
  private val freeHand: EquipmentSlot                = new OffhandEquipmentSlot(EquipmentSize.Inventory)
  private val holsters: Array[EquipmentSlot]         = Array.fill[EquipmentSlot](5)(new EquipmentSlot)
  private val inventory: GridInventory               = GridInventory()
  private var drawnSlot: Int                         = Player.HandsDownSlot
  private var lastDrawnSlot: Int                     = Player.HandsDownSlot
  private var backpackAccess: Option[PlanetSideGUID] = None
  private var carrying: Option[SpecialCarry]         = None

  private var facingYawUpper: Float       = 0f
  private var crouching: Boolean          = false
  private var jumping: Boolean            = false
  private var cloaked: Boolean            = false
  private var afk: Boolean                = false
  private var zoning: Zoning.Method       = Zoning.Method.None

  private var vehicleSeated: Option[PlanetSideGUID] = None

  Continent = "home2" //the zone id

  var spectator: Boolean                 = false
  var bops: Boolean                      = false
  var silenced: Boolean                  = false
  var death_by: Int                      = 0
  var lastShotSeq_time: Int              = -1

  /** From PlanetsideAttributeMessage */
  var PlanetsideAttribute: Array[Long] = Array.ofDim(120)

  val squadLoadouts = new LoadoutManager(10)

  var resistArmMotion: (Player,Int)=>Boolean = Player.neverRestrict

  //init
  Health = 0       //player health is artificially managed as a part of their lifecycle; start entity as dead
  Destroyed = true //see isAlive
  Player.SuitSetup(this, exosuit)

  def Definition: AvatarDefinition = avatar.definition

  def CharId: Long = avatar.id

  def Name: String = avatar.name

  def Faction: PlanetSideEmpire.Value = avatar.faction

  def Sex: CharacterSex = avatar.sex

  def Head: Int = avatar.head

  def Voice: CharacterVoice.Value = avatar.voice

  def isAlive: Boolean = !Destroyed

  def isBackpack: Boolean = backpack

  def Spawn(): Boolean = {
    if (!isAlive && !isBackpack) {
      Destroyed = false
      Health = Definition.DefaultHealth
      Armor = MaxArmor
      Capacitor = 0
      avatar.scorecard.respawn()
      released = false
    }
    isAlive
  }

  def Die: Boolean = {
    Destroyed = true
    Health = 0
    false
  }

  def Revive: Boolean = {
    Destroyed = false
    Health = Definition.DefaultHealth
    avatar.scorecard.revive()
    released = false
    true
  }

  def Release: Boolean = {
    if (!released) {
      released = true
      backpack = !isAlive
    }
    true
  }

  def isReleased: Boolean = released

  def Armor: Int = armor

  def Armor_=(assignArmor: Int): Int = {
    armor = math.min(math.max(0, assignArmor), MaxArmor)
    Armor
  }

  def MaxArmor: Int = exosuit.MaxArmor

  def Capacitor: Float = capacitor

  def Capacitor_=(value: Float): Float = {
    val newValue = math.min(math.max(0, value), ExoSuitDef.MaxCapacitor.toFloat)

    if (newValue < capacitor) {
      capacitorLastUsedMillis = System.currentTimeMillis()
      capacitorLastChargedMillis = 0
    } else if (newValue > capacitor && newValue < ExoSuitDef.MaxCapacitor) {
      capacitorLastChargedMillis = System.currentTimeMillis()
      capacitorLastUsedMillis = 0
    } else if (newValue > capacitor && newValue == ExoSuitDef.MaxCapacitor) {
      capacitorLastChargedMillis = 0
      capacitorLastUsedMillis = 0
      capacitorState = CapacitorStateType.Idle
    }

    capacitor = newValue
    capacitor
  }

  def CapacitorState: CapacitorStateType.Value = capacitorState
  def CapacitorState_=(value: CapacitorStateType.Value): CapacitorStateType.Value = {
    value match {
      case CapacitorStateType.Charging    => capacitorLastChargedMillis = System.currentTimeMillis()
      case CapacitorStateType.Discharging => capacitorLastUsedMillis = System.currentTimeMillis()
      case _                              => ;
    }

    capacitorState = value
    capacitorState
  }

  def CapacitorLastUsedMillis: Long    = capacitorLastUsedMillis
  def CapacitorLastChargedMillis: Long = capacitorLastChargedMillis

  def VisibleSlots: Set[Int] =
    if (exosuit.SuitType == ExoSuitType.MAX) {
      Set(0)
    } else {
      (0 to 4).filterNot(index => holsters(index).Size == EquipmentSize.Blocked).toSet
    }

  override def Slot(slot: Int): EquipmentSlot = {
    if (inventory.Offset <= slot && slot <= inventory.LastIndex) {
      inventory.Slot(slot)
    } else if (slot > -1 && slot < 5) {
      holsters(slot)
    } else if (slot == 5) {
      avatar.fifthSlot()
    } else if (slot == Player.FreeHandSlot) {
      freeHand
    } else {
      OffhandEquipmentSlot.BlockedSlot
    }
  }

  def Holsters(): Array[EquipmentSlot] = holsters

  /**
    * Transform the holster equipment slots
    * into a list of the kind of item wrapper found in an inventory.
    * @see `GridInventory`
    * @see `InventoryItem`
    * @return a list of items that would be found in a proper inventory
    */
  def HolsterItems(): List[InventoryItem] = holsters
    .zipWithIndex
    .collect {
      case (slot: EquipmentSlot, index: Int) =>
        slot.Equipment match {
          case Some(item) => Some(InventoryItem(item, index))
          case None => None
        }
    }.flatten.toList

  def Inventory: GridInventory = inventory

  override def Fit(obj: Equipment): Option[Int] = {
    recursiveHolsterFit(holsters.iterator, obj.Size) match {
      case Some(index) =>
        Some(index)
      case None =>
        inventory.Fit(obj.Definition.Tile) match {
          case Some(index) =>
            Some(index)
          case None =>
            if (freeHand.Equipment.isDefined) { None }
            else { Some(Player.FreeHandSlot) }
        }
    }
  }

  @tailrec private def recursiveHolsterFit(
      iter: Iterator[EquipmentSlot],
      objSize: EquipmentSize.Value,
      index: Int = 0
  ): Option[Int] = {
    if (!iter.hasNext) {
      None
    } else {
      val slot = iter.next()
      if (slot.Equipment.isEmpty && slot.Size.equals(objSize)) {
        Some(index)
      } else {
        recursiveHolsterFit(iter, objSize, index + 1)
      }
    }
  }

  def FreeHand: EquipmentSlot = freeHand

  def FreeHand_=(item: Option[Equipment]): Option[Equipment] = {
    if (freeHand.Equipment.isEmpty || item.isEmpty) {
      freeHand.Equipment = item
    }
    FreeHand.Equipment
  }

  override def Find(guid: PlanetSideGUID): Option[Int] = {
    findInHolsters(holsters.iterator, guid)
      .orElse(inventory.Find(guid)) match {
      case Some(index) =>
        Some(index)
      case None =>
        if (freeHand.Equipment.isDefined && freeHand.Equipment.get.GUID == guid) {
          Some(Player.FreeHandSlot)
        } else {
          None
        }
    }
  }

  @tailrec private def findInHolsters(
      iter: Iterator[EquipmentSlot],
      guid: PlanetSideGUID,
      index: Int = 0
  ): Option[Int] = {
    if (!iter.hasNext) {
      None
    } else {
      val slot = iter.next()
      if (slot.Equipment match { case Some(o) => o.GUID == guid; case _ => false }) {
        Some(index)
      } else {
        findInHolsters(iter, guid, index + 1)
      }
    }
  }

  override def Collisions(dest: Int, width: Int, height: Int): Try[List[InventoryItem]] = {
    if (-1 < dest && dest < 5) {
      holsters(dest).Equipment match {
        case Some(item) =>
          Success(List(InventoryItem(item, dest)))
        case None =>
          Success(List())
      }
    } else if (dest == Player.FreeHandSlot) {
      freeHand.Equipment match {
        case Some(item) =>
          Success(List(InventoryItem(item, dest)))
        case None =>
          Success(List())
      }
    } else {
      super.Collisions(dest, width, height)
    }
  }

  def ResistArmMotion(func: (Player,Int)=>Boolean): Unit = {
    resistArmMotion = func
  }

  def TestArmMotion(): Boolean = {
    resistArmMotion(this, drawnSlot)
  }

  def TestArmMotion(slot: Int): Boolean = {
    resistArmMotion(this, slot)
  }

  def DrawnSlot: Int = drawnSlot

  def DrawnSlot_=(slot: Int): Int = {
    if (slot != drawnSlot) {
      if (slot == Player.HandsDownSlot) {
        drawnSlot = slot
      } else if (VisibleSlots.contains(slot) && holsters(slot).Equipment.isDefined) {
        drawnSlot = slot
        lastDrawnSlot = slot
      }
    }
    DrawnSlot
  }

  def LastDrawnSlot: Int = lastDrawnSlot

  def ExoSuit: ExoSuitType.Value    = exosuit.SuitType
  def ExoSuitDef: ExoSuitDefinition = exosuit

  def ExoSuit_=(suit: ExoSuitType.Value): Unit = {
    val eSuit = ExoSuitDefinition.Select(suit, Faction)
    exosuit = eSuit
    Player.SuitSetup(this, eSuit)
    ChangeSpecialAbility()
  }

  def Subtract: DamageProfile = exosuit.Subtract

  def ResistanceDirectHit: Int = exosuit.ResistanceDirectHit

  def ResistanceSplash: Int = exosuit.ResistanceSplash

  def ResistanceAggravated: Int = exosuit.ResistanceAggravated

  def RadiationShielding: Float = exosuit.RadiationShielding

  def FacingYawUpper: Float = facingYawUpper

  def FacingYawUpper_=(facing: Float): Float = {
    facingYawUpper = facing
    FacingYawUpper
  }

  def Crouching: Boolean = crouching

  def Crouching_=(crouched: Boolean): Boolean = {
    crouching = crouched
    Crouching
  }

  def Jumping: Boolean = jumping

  def Jumping_=(jumped: Boolean): Boolean = {
    jumping = jumped
    Jumping
  }

  def Cloaked: Boolean = cloaked

  def Cloaked_=(isCloaked: Boolean): Boolean = {
    cloaked = isCloaked
    Cloaked
  }

  def AwayFromKeyboard: Boolean = afk

  def AwayFromKeyboard_=(away: Boolean): Boolean = {
    afk = away
    AwayFromKeyboard
  }

  private var usingSpecial: SpecialExoSuitDefinition.Mode.Value => SpecialExoSuitDefinition.Mode.Value =
    DefaultUsingSpecial

  private var gettingSpecial: () => SpecialExoSuitDefinition.Mode.Value = DefaultGettingSpecial

  private def ChangeSpecialAbility(): Unit = {
    if (ExoSuit == ExoSuitType.MAX) {
      gettingSpecial = MAXGettingSpecial
      usingSpecial = Faction match {
        case PlanetSideEmpire.TR => UsingAnchorsOrOverdrive
        case PlanetSideEmpire.NC => UsingShield
        case _                   => DefaultUsingSpecial
      }
    } else {
      usingSpecial = DefaultUsingSpecial
      gettingSpecial = DefaultGettingSpecial
    }
  }

  def UsingSpecial: SpecialExoSuitDefinition.Mode.Value = { gettingSpecial() }

  def UsingSpecial_=(state: SpecialExoSuitDefinition.Mode.Value): SpecialExoSuitDefinition.Mode.Value =
    usingSpecial(state)

  //noinspection ScalaUnusedSymbol
  private def DefaultUsingSpecial(state: SpecialExoSuitDefinition.Mode.Value): SpecialExoSuitDefinition.Mode.Value =
    SpecialExoSuitDefinition.Mode.Normal

  private def UsingAnchorsOrOverdrive(
                                       state: SpecialExoSuitDefinition.Mode.Value
                                     ): SpecialExoSuitDefinition.Mode.Value = {
    import SpecialExoSuitDefinition.Mode._
    val curr = UsingSpecial
    val next = if (curr == Normal) {
      if (state == Anchored || state == Overdrive) {
        state
      } else {
        Normal
      }
    } else if (state == Normal) {
      Normal
    } else {
      curr
    }
    MAXUsingSpecial(next)
  }

  private def UsingShield(state: SpecialExoSuitDefinition.Mode.Value): SpecialExoSuitDefinition.Mode.Value = {
    import SpecialExoSuitDefinition.Mode._
    val curr = UsingSpecial
    val next = if (curr == Normal) {
      if (state == Shielded) {
        state
      } else {
        Normal
      }
    } else if (state == Normal) {
      Normal
    } else {
      curr
    }
    MAXUsingSpecial(next)
  }

  private def DefaultGettingSpecial(): SpecialExoSuitDefinition.Mode.Value = SpecialExoSuitDefinition.Mode.Normal

  private def MAXUsingSpecial(state: SpecialExoSuitDefinition.Mode.Value): SpecialExoSuitDefinition.Mode.Value =
    exosuit match {
      case obj: SpecialExoSuitDefinition =>
        obj.UsingSpecial = state
      case _ =>
        SpecialExoSuitDefinition.Mode.Normal
    }

  private def MAXGettingSpecial(): SpecialExoSuitDefinition.Mode.Value =
    exosuit match {
      case obj: SpecialExoSuitDefinition =>
        obj.UsingSpecial
      case _ =>
        SpecialExoSuitDefinition.Mode.Normal
    }

  def isAnchored: Boolean =
    ExoSuit == ExoSuitType.MAX && Faction == PlanetSideEmpire.TR && UsingSpecial == SpecialExoSuitDefinition.Mode.Anchored

  def isOverdrived: Boolean =
    ExoSuit == ExoSuitType.MAX && Faction == PlanetSideEmpire.TR && UsingSpecial == SpecialExoSuitDefinition.Mode.Overdrive

  def isShielded: Boolean =
    ExoSuit == ExoSuitType.MAX && Faction == PlanetSideEmpire.NC && UsingSpecial == SpecialExoSuitDefinition.Mode.Shielded

  def AccessingBackpack: Option[PlanetSideGUID] = backpackAccess

  def AccessingBackpack_=(guid: PlanetSideGUID): Option[PlanetSideGUID] = {
    AccessingBackpack = Some(guid)
  }

  /**
    * Change which player has access to the backpack of this player.
    * A player may only access to the backpack of a dead released player, and only if no one else has access at the moment.
    * @param guid the player who wishes to access the backpack
    * @return the player who is currently allowed to access the backpack
    */
  def AccessingBackpack_=(guid: Option[PlanetSideGUID]): Option[PlanetSideGUID] = {
    guid match {
      case None =>
        backpackAccess = None
      case Some(player) =>
        if (isBackpack && backpackAccess.isEmpty) {
          backpackAccess = Some(player)
        }
    }
    AccessingBackpack
  }

  /**
    * Can the other `player` access the contents of this `Player`'s backpack?
    * @param player a player attempting to access this backpack
    * @return `true`, if the `player` is permitted access; `false`, otherwise
    */
  def CanAccessBackpack(player: Player): Boolean = {
    isBackpack && (backpackAccess.isEmpty || backpackAccess.contains(player.GUID))
  }

  def VehicleSeated: Option[PlanetSideGUID] = vehicleSeated

  def VehicleSeated_=(guid: PlanetSideGUID): Option[PlanetSideGUID] = VehicleSeated_=(Some(guid))

  def VehicleSeated_=(guid: Option[PlanetSideGUID]): Option[PlanetSideGUID] = {
    vehicleSeated = guid
    VehicleSeated
  }

  def Carrying: Option[SpecialCarry] = carrying

  //noinspection ScalaUnusedSymbol
  def Carrying_=(item: SpecialCarry): Option[SpecialCarry] = {
    Carrying_=(Some(item))
  }

  //noinspection ScalaUnusedSymbol
  def Carrying_=(item: Option[SpecialCarry]): Option[SpecialCarry] = {
    carrying = item
    Carrying
  }

  def ZoningRequest: Zoning.Method = zoning

  def ZoningRequest_=(request: Zoning.Method): Zoning.Method = {
    zoning = request
    ZoningRequest
  }

  override def CanDamage: Boolean = {
    death_by < 1 && super.CanDamage
  }

  def DamageModel: DamageResistanceModel = exosuit.asInstanceOf[DamageResistanceModel]

  override def GetContributionDuringPeriod(list: List[InGameActivity], duration: Long): List[InGameActivity] = {
    val earliestEndTime = System.currentTimeMillis() - duration
    History.collect {
      case heal: HealFromEquipment if heal.amount > 0 && heal.time > earliestEndTime         => heal
      case repair: RepairFromEquipment if repair.amount > 0 && repair.time > earliestEndTime => repair
    }
  }

  def canEqual(other: Any): Boolean = other.isInstanceOf[Player]

  override def equals(other: Any): Boolean =
    other match {
      case that: Player =>
        (that canEqual this) &&
          avatar == that.avatar
      case _ =>
        false
    }

  override def hashCode(): Int = {
    avatar.hashCode()
  }

  override def toString: String = {
    val guid = if (HasGUID) {
      s" $Continent-${GUID.guid}"
    } else {
      ""
    }
    s"${avatar.name}$guid ${avatar.faction} H: $Health/$MaxHealth A: $Armor/$MaxArmor"
  }
}

object Player {
  final val LockerSlot: Int    = 5
  final val FreeHandSlot: Int  = 250
  final val HandsDownSlot: Int = 255

  final case class BuildDeployable(obj: Deployable, withTool: ConstructionItem)

  final case class LoseDeployable(obj: Deployable)

  final case class Die(reason: Option[DamageInteraction])

  object Die {
    def apply(): Die = Die(None)

    def apply(reason: DamageInteraction): Die = {
      Die(Some(reason))
    }
  }

  def apply(core: Avatar): Player = {
    new Player(core)
  }

  private def SuitSetup(player: Player, eSuit: ExoSuitDefinition): Unit = {
    //inventory
    player.Inventory.Clear()
    player.Inventory.Resize(eSuit.InventoryScale.Width, eSuit.InventoryScale.Height)
    player.Inventory.Offset = eSuit.InventoryOffset
    //holsters
    (0 until 5).foreach { index => player.Slot(index).Size = eSuit.Holster(index) }
  }

  def Respawn(player: Player): Player = {
    if (player.Release) {
      val obj = new Player(player.avatar)
      obj.Continent = player.Continent
      obj.death_by = player.death_by
      obj.silenced = player.silenced
      obj.allowInteraction = player.allowInteraction
      obj.avatar.scorecard.respawn()
      obj
    } else {
      player
    }
  }

  //noinspection ScalaUnusedSymbol
  def neverRestrict(player: Player, slot: Int): Boolean = {
    false
  }
}
