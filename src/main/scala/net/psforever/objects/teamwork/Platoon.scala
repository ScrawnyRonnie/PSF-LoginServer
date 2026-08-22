// Copyright (c) 2026 PSForever
package net.psforever.objects.teamwork

import net.psforever.objects.entity.IdentifiableEntity
import net.psforever.types.{PlanetSideEmpire, PlanetSideGUID}

/**
  * A platoon is a group of 2-3 squads.
  * The platoon is organized and commanded by the leader of the first squad to join.
  * Additional squads can be invited to join the platoon, and squads can leave.
  *
  * @param platoonId the unique identifier for this platoon
  * @param faction   the empire this platoon belongs to
  */
class Platoon(platoonId: PlanetSideGUID, faction: PlanetSideEmpire.Value) extends IdentifiableEntity {
  super.GUID_=(platoonId)
  private val alignment: PlanetSideEmpire.Value = faction
  private var commander: Option[Long] = None  // CharId of the platoon commander (first squad leader)
  private val squads: scala.collection.mutable.Map[Int, Squad] = scala.collection.mutable.Map()  // Index -> Squad
  private var nextSquadIndex: Int = 0

  override def GUID_=(d: PlanetSideGUID): PlanetSideGUID = GUID

  def Faction: PlanetSideEmpire.Value = alignment

  def Commander: Option[Long] = commander

  def Commander_=(charId: Option[Long]): Option[Long] = {
    commander = charId
    Commander
  }

  /**
    * Get all squads in this platoon in order
    */
  def Squads: List[Squad] = {
    (0 until nextSquadIndex)
      .flatMap(i => squads.get(i))
      .toList
  }

  /**
    * Get a squad by its position in the platoon
    * @param index the position (0, 1, or 2)
    * @return the squad at that position, or None
    */
  def Squad(index: Int): Option[Squad] = squads.get(index)

  /**
    * Add a squad to the platoon at the next available position
    * @param squad the squad to add
    * @return the index where the squad was added, or -1 if the platoon is full
    */
  def AddSquad(squad: Squad): Int = {
    if (nextSquadIndex >= 3) {
      -1  // Platoon is full
    } else {
      val index = nextSquadIndex
      squads(index) = squad
      nextSquadIndex += 1
      
      // Set platoon commander to the leader of the first squad
      if (index == 0) {
        commander = Some(squad.Leader.CharId)
      }
      
      index
    }
  }

  /**
    * Remove a squad from the platoon by its index
    * @param index the position of the squad to remove
    * @return true if the squad was removed, false if not found
    */
  def RemoveSquad(index: Int): Boolean = {
    squads.remove(index).isDefined
  }

  /**
    * Remove a squad from the platoon by its GUID
    * @param squadGuid the GUID of the squad to remove
    * @return the index of the removed squad, or -1 if not found
    */
  def RemoveSquadByGuid(squadGuid: PlanetSideGUID): Int = {
    val foundIndex = squads.find { case (_, squad) => squad.GUID == squadGuid }.map(_._1)
    foundIndex match {
      case Some(index) =>
        RemoveSquad(index)
        index
      case None =>
        -1
    }
  }

  /**
    * Get the number of squads in this platoon
    */
  def Size: Int = squads.size

  /**
    * Check if the platoon is full (3 squads)
    */
  def IsFull: Boolean = nextSquadIndex >= 3

  /**
    * Check if the platoon meets minimum requirements (2 squads)
    */
  def IsValid: Boolean = squads.size >= 2

  /**
    * Find which squad contains a member by their character ID
    * @param charId the character ID to search for
    * @return tuple of (squad, index) or None
    */
  def FindSquadContainingMember(charId: Long): Option[(Squad, Int)] = {
    squads.collectFirst {
      case (index, squad) if squad.Membership.exists(_.CharId == charId) =>
        (squad, index)
    }
  }

  /**
    * Get all member character IDs across all squads
    */
  def AllMembers: Set[Long] = {
    squads.values.flatMap { squad =>
      squad.Membership.collect { case member if member.CharId > 0 => member.CharId }
    }.toSet
  }
}

object Platoon {
  final val Blank = new Platoon(PlanetSideGUID(0), PlanetSideEmpire.NEUTRAL) {
    override def Squads: List[Squad] = List()
  }
}
