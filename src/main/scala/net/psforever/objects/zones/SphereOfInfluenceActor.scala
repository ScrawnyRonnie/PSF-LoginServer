package net.psforever.objects.zones

import akka.actor.{Actor, Cancellable}
import net.psforever.objects.Default
import net.psforever.objects.serverobject.structures.Building
import net.psforever.types.Vector3

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class SphereOfInfluenceActor(zone: Zone) extends Actor {
  var sois: Iterable[(Building, Int)] = Nil
  var populateTick: Cancellable       = Default.Cancellable
  //private[this] val log = org.log4s.getLogger(s"${zone.Id.capitalize}-SphereOfInfluenceActor")

  def receive: Receive = Stopped

  def Build: Receive = {
    case SOI.Build() =>
      BuildSOI()
  }

  def Running: Receive =
    Build.orElse {
      case SOI.Populate() =>
        UpdateSOI()

      case SOI.Stop() =>
        context.become(Stopped)
        populateTick.cancel()
        sois.foreach { case (facility, _) => facility.PlayersInSOI = Nil }

      case _ => ;
    }

  def Stopped: Receive =
    Build.orElse {
      case SOI.Start() if sois.nonEmpty =>
        context.become(Running)
        UpdateSOI()

      case _ => ;
    }

  def BuildSOI(): Unit = {
    sois = zone.Buildings.values
      .map { facility => (facility, facility.Definition) }
      .collect {
        case (facility, soi) if soi.SOIRadius > 0 =>
          (facility, soi.SOIRadius * soi.SOIRadius)
      }
  }

  def UpdateSOI(): Unit = {
    sois.foreach {
      case (facility, radius) =>
        val facilityXY = facility.Position.xy
        val playersOnFoot = zone.blockMap.sector(facility)
          .livePlayerList
          .filter(p => Vector3.DistanceSquared(facilityXY, p.Position.xy) < radius)

        val vehicleOccupants = zone.blockMap.sector(facility)
          .vehicleList
          .filter(v => Vector3.DistanceSquared(facilityXY, v.Position.xy) < radius)
          .flatMap(_.Seats.values.flatMap(_.occupants))

        facility.PlayersInSOI = playersOnFoot ++ vehicleOccupants
    }
    populateTick.cancel()
    populateTick = context.system.scheduler.scheduleOnce(5 seconds, self, SOI.Populate())
  }
}

object SOI {

  /** Rebuild the list of facility SOI data * */
  final case class Build()

  /** Populate the list of players within a SOI * */
  final case class Populate()

  /** Stop sorting players into sois */
  final case class Start()

  /** Stop sorting players into sois */
  final case class Stop()
}
