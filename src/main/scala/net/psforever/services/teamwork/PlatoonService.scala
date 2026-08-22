// Copyright (c) 2026 PSForever
package net.psforever.services.teamwork

import net.psforever.objects.teamwork.{Platoon, Squad}
import net.psforever.types.{PlanetSideEmpire, PlanetSideGUID}
import scala.collection.mutable

/**
  * Manages all active platoons organized by faction and GUID.
  * Provides lookup and mutation operations for platoon state.
  */
class PlatoonService {

  /**
    * All platoons indexed by their GUID
    */
  private val platoonsByGuid: mutable.Map[PlanetSideGUID, Platoon] = mutable.Map()

  /**
    * Platoons indexed by faction for quick lookup
    */
  private val platoonsByFaction: mutable.Map[PlanetSideEmpire.Value, mutable.Set[Platoon]] =
    mutable.Map(
      PlanetSideEmpire.TR -> mutable.Set(),
      PlanetSideEmpire.NC -> mutable.Set(),
      PlanetSideEmpire.VS -> mutable.Set()
    )

  /**
    * Mapping from squad GUID to platoon GUID for quick lookups
    */
  private val squadToPlatoon: mutable.Map[PlanetSideGUID, PlanetSideGUID] = mutable.Map()

  /**
    * Create a new platoon and register it
    * @param platoonGuid unique identifier for the platoon
    * @param faction the empire this platoon belongs to
    * @return the newly created platoon
    */
  def CreatePlatoon(platoonGuid: PlanetSideGUID, faction: PlanetSideEmpire.Value): Platoon = {
    val platoon = new Platoon(platoonGuid, faction)
    platoonsByGuid(platoonGuid) = platoon
    platoonsByFaction(faction).add(platoon)
    platoon
  }

  /**
    * Get a platoon by its GUID
    * @param platoonGuid the platoon's unique identifier
    * @return the platoon, or None if not found
    */
  def GetPlatoon(platoonGuid: PlanetSideGUID): Option[Platoon] = {
    platoonsByGuid.get(platoonGuid)
  }

  /**
    * Get all platoons for a specific faction
    * @param faction the empire to query
    * @return all platoons belonging to that faction
    */
  def GetPlatoonsByFaction(faction: PlanetSideEmpire.Value): Set[Platoon] = {
    platoonsByFaction.getOrElse(faction, mutable.Set()).toSet
  }

  /**
    * Find the platoon that contains a specific squad
    * @param squadGuid the squad's unique identifier
    * @return the platoon containing this squad, or None
    */
  def GetPlatoonBySquad(squadGuid: PlanetSideGUID): Option[Platoon] = {
    squadToPlatoon.get(squadGuid).flatMap(platoonsByGuid.get)
  }

  /**
    * Add a squad to an existing platoon
    * @param platoonGuid the platoon to add to
    * @param squad the squad to add
    * @return the index of the squad in the platoon, or -1 if failed
    */
  def AddSquadToPlatoon(platoonGuid: PlanetSideGUID, squad: Squad): Int = {
    GetPlatoon(platoonGuid) match {
      case Some(platoon) if !platoon.IsFull =>
        val index = platoon.AddSquad(squad)
        if (index >= 0) {
          squadToPlatoon(squad.GUID) = platoonGuid
        }
        index
      case _ =>
        -1  // Platoon not found or is full
    }
  }

  /**
    * Remove a squad from its platoon
    * @param squadGuid the squad to remove
    * @return true if the squad was removed, false if not found
    */
  def RemoveSquadFromPlatoon(squadGuid: PlanetSideGUID): Boolean = {
    squadToPlatoon.get(squadGuid) match {
      case Some(platoonGuid) =>
        platoonsByGuid.get(platoonGuid) match {
          case Some(platoon) =>
            val index = platoon.RemoveSquadByGuid(squadGuid)
            if (index >= 0) {
              squadToPlatoon.remove(squadGuid)
              
              // If platoon no longer meets minimum requirements, disband it
              if (!platoon.IsValid) {
                DisbandPlatoon(platoonGuid)
              }
              true
            } else {
              false
            }
          case None =>
            false
        }
      case None =>
        false
    }
  }

  /**
    * Disband a platoon and remove all its squads from tracking
    * @param platoonGuid the platoon to disband
    * @return true if the platoon was disbanded, false if not found
    */
  def DisbandPlatoon(platoonGuid: PlanetSideGUID): Boolean = {
    platoonsByGuid.get(platoonGuid) match {
      case Some(platoon) =>
        // Remove all squad mappings
        platoon.Squads.foreach { squad =>
          squadToPlatoon.remove(squad.GUID)
        }
        
        // Remove from faction index
        platoonsByFaction(platoon.Faction).remove(platoon)
        
        // Remove the platoon itself
        platoonsByGuid.remove(platoonGuid)
        true
      case None =>
        false
    }
  }

  /**
    * Get all members in a platoon
    * @param platoonGuid the platoon's unique identifier
    * @return set of all character IDs in the platoon
    */
  def GetPlatoonMembers(platoonGuid: PlanetSideGUID): Set[Long] = {
    GetPlatoon(platoonGuid) match {
      case Some(platoon) =>
        platoon.AllMembers
      case None =>
        Set.empty
    }
  }

  /**
    * Check if a character is in a platoon
    * @param charId the character's unique identifier
    * @return the platoon GUID if found, or None
    */
  def FindPlatoonByMember(charId: Long): Option[PlanetSideGUID] = {
    platoonsByGuid.collectFirst {
      case (guid, platoon) if platoon.AllMembers.contains(charId) => guid
    }
  }

  /**
    * Get the size of a platoon
    * @param platoonGuid the platoon's unique identifier
    * @return the number of squads, or 0 if not found
    */
  def GetPlatoonSize(platoonGuid: PlanetSideGUID): Int = {
    GetPlatoon(platoonGuid).map(_.Size).getOrElse(0)
  }
}

object PlatoonService {
  private val instance = new PlatoonService()

  def apply(): PlatoonService = instance

  def getInstance: PlatoonService = instance
}
