// Copyright (c) 2026 PSForever
package net.psforever.services.teamwork

import net.psforever.objects.teamwork.{Platoon, Squad}
import net.psforever.types.PlanetSideGUID
import net.psforever.services.base.message.EventResponse

/**
  * Sealed trait for all platoon-related service responses
  */
sealed trait PlatoonResponse extends EventResponse

object PlatoonResponse {

  /**
    * Notification that a squad was added to a platoon
    * @param platoon_guid the platoon's GUID
    * @param squad_guid the squad's GUID
    * @param squad_index the position of the squad in the platoon (0-2)
    * @param squad_size the number of members in the squad
    */
  final case class SquadAdded(
      platoon_guid: PlanetSideGUID,
      squad_guid: PlanetSideGUID,
      squad_index: Int,
      squad_size: Int
  ) extends PlatoonResponse

  /**
    * Notification that a squad was removed from a platoon
    * @param platoon_guid the platoon's GUID
    * @param squad_guid the squad's GUID
    * @param squad_index the position the squad occupied
    * @param platoon_still_valid whether the platoon still meets minimum requirements
    */
  final case class SquadRemoved(
      platoon_guid: PlanetSideGUID,
      squad_guid: PlanetSideGUID,
      squad_index: Int,
      platoon_still_valid: Boolean
  ) extends PlatoonResponse

  /**
    * Notification that a platoon was disbanded
    * @param platoon_guid the platoon's GUID
    * @param reason why it was disbanded (e.g., "MinimumSizeNotMet", "LeaderRequest")
    */
  final case class PlatoonDisbanded(
      platoon_guid: PlanetSideGUID,
      reason: String
  ) extends PlatoonResponse

  /**
    * Notification that a platoon was created
    * @param platoon_guid the platoon's GUID
    * @param commander_char_id the character ID of the platoon commander
    * @param faction the empire this platoon belongs to
    */
  final case class PlatoonCreated(
      platoon_guid: PlanetSideGUID,
      commander_char_id: Long,
      faction: String
  ) extends PlatoonResponse

  /**
    * Request to change the platoon commander
    * @param platoon_guid the platoon's GUID
    * @param new_commander_char_id the character ID of the new commander
    */
  final case class ChangeCommander(
      platoon_guid: PlanetSideGUID,
      new_commander_char_id: Long
  ) extends PlatoonResponse

  /**
    * Update with current platoon state
    * @param platoon_guid the platoon's GUID
    * @param platoon the platoon object
    */
  final case class PlatoonState(
      platoon_guid: PlanetSideGUID,
      platoon: Platoon
  ) extends PlatoonResponse

  /**
    * Invitation result (accepted or rejected)
    * @param inviting_squad_leader_char_id the character ID of the inviting squad leader
    * @param invited_squad_leader_char_id the character ID of the invited squad leader
    * @param platoon_guid the platoon being invited to (if accepted)
    * @param accepted whether the invitation was accepted
    */
  final case class InvitationResult(
      inviting_squad_leader_char_id: Long,
      invited_squad_leader_char_id: Long,
      platoon_guid: Option[PlanetSideGUID],
      accepted: Boolean
  ) extends PlatoonResponse
}
