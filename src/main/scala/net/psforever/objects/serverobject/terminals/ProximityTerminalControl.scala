// Copyright (c) 2017 PSForever
package net.psforever.objects.serverobject.terminals

import akka.actor.{Actor, ActorRef, Cancellable}
import net.psforever.objects._
import net.psforever.objects.serverobject.CommonMessages
import net.psforever.objects.serverobject.affinity.FactionAffinityBehavior
import net.psforever.objects.serverobject.damage.DamageableAmenity
import net.psforever.objects.serverobject.hackable.{GenericHackables, HackableBehavior}
import net.psforever.objects.serverobject.repair.RepairableAmenity
import net.psforever.objects.serverobject.structures.Building

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * An `Actor` that handles messages being dispatched to a specific `ProximityTerminal`.
  * Although this "terminal" itself does not accept the same messages as a normal `Terminal` object,
  * it returns the same type of messages - wrapped in a `TerminalMessage` - to the `sender`.
  * @param term the proximity unit (terminal)
  */
class ProximityTerminalControl(term: Terminal with ProximityUnit)
    extends Actor
    with FactionAffinityBehavior.Check
    with HackableBehavior.GenericHackable
    with DamageableAmenity
    with RepairableAmenity {
  def FactionObject    = term
  def HackableObject   = term
  def TerminalObject   = term
  def DamageableObject = term
  def RepairableObject = term

  var terminalAction: Cancellable             = Default.Cancellable
  val callbacks: mutable.ListBuffer[ActorRef] = new mutable.ListBuffer[ActorRef]()
  val log                                     = org.log4s.getLogger

  def receive: Receive =
    checkBehavior
      .orElse(hackableBehavior)
      .orElse(takesDamage)
      .orElse(canBeRepairedByNanoDispenser)
      .orElse {
        case CommonMessages.Use(player, Some(item: SimpleItem))
            if item.Definition == GlobalDefinitions.remote_electronics_kit =>
          //TODO setup certifications check
          term.Owner match {
            case b: Building if (b.Faction != player.Faction || b.CaptureTerminalIsHacked) && term.HackedBy.isEmpty =>
              sender() ! CommonMessages.Progress(
                GenericHackables.GetHackSpeed(player, term),
                GenericHackables.FinishHacking(term, player, 3212836864L),
                GenericHackables.HackingTickAction(progressType = 1, player, term, item.GUID)
              )
            case _ => ;
          }

        case CommonMessages.Use(_, Some(target: PlanetSideGameObject)) =>
          if (!term.Destroyed && term.Definition.asInstanceOf[ProximityDefinition].Validations.exists(p => p(target))) {
            Use(target, term.Continent, sender())
          }
        case CommonMessages.Use(_, Some((target: PlanetSideGameObject, callback: ActorRef))) =>
          if (!term.Destroyed && term.Definition.asInstanceOf[ProximityDefinition].Validations.exists(p => p(target))) {
            Use(target, term.Continent, callback)
          }

        case CommonMessages.Use(_, _) =>
          log.warn(s"unexpected format for CommonMessages.Use in this context")

        case CommonMessages.Unuse(_, Some(target: PlanetSideGameObject)) =>
          Unuse(target, term.Continent)

        case CommonMessages.Unuse(_, _) =>
          log.warn(s"unexpected format for CommonMessages.Unuse in this context")

        case ProximityTerminalControl.TerminalAction() =>
          val proxDef = term.Definition.asInstanceOf[ProximityDefinition]
          val validateFunc: PlanetSideGameObject => Boolean =
            term.Validate(proxDef.UseRadius * proxDef.UseRadius, proxDef.Validations)
          val callbackList = callbacks.toList
          term.Targets.zipWithIndex.foreach({
            case (target, index) =>
              if (!term.Destroyed && validateFunc(target)) {
                callbackList.lift(index) match {
                  case Some(cback) =>
                    cback ! ProximityUnit.Action(term, target)
                  case None =>
                    log.error(
                      s"improper callback registered for $target on $term in zone ${term.Owner.Continent}; this may be recoverable"
                    )
                }
              } else {
                Unuse(target, term.Continent)
              }
          })

        case ProximityUnit.Action(_, _) =>
        //reserved

        case _ =>
      }

  def Use(target: PlanetSideGameObject, zone: String, callback: ActorRef): Unit = {
    val hadNoUsers = term.NumberUsers == 0
    if (term.AddUser(target)) {
      log.trace(s"ProximityTerminal.Use: unit ${term.Definition.Name}@${term.GUID.guid} will act on $target")
      //add callback
      callbacks += callback
      //activation
      if (term.NumberUsers == 1 && hadNoUsers) {
        val medDef = term.Definition.asInstanceOf[MedicalTerminalDefinition]
        import scala.concurrent.ExecutionContext.Implicits.global
        terminalAction.cancel()
        terminalAction = context.system.scheduler.scheduleWithFixedDelay(
          500 milliseconds,
          medDef.Interval,
          self,
          ProximityTerminalControl.TerminalAction()
        )
        TerminalObject.Zone.LocalEvents ! Terminal.StartProximityEffect(term)
      }
    } else {
      log.warn(s"ProximityTerminal.Use: $target was rejected by unit ${term.Definition.Name}@${term.GUID.guid}")
    }
  }

  def Unuse(target: PlanetSideGameObject, zone: String): Unit = {
    val whereTarget   = term.Targets.indexWhere(_ eq target)
    val previousUsers = term.NumberUsers
    val hadUsers      = previousUsers > 0
    if (whereTarget > -1 && term.RemoveUser(target)) {
      log.trace(
        s"ProximityTerminal.Unuse: unit ${term.Definition.Name}@${term.GUID.guid} will cease operation on $target"
      )
      //remove callback
      callbacks.remove(whereTarget)
      //de-activation (global / local)
      if (term.NumberUsers == 0 && hadUsers) {
        terminalAction.cancel()
        TerminalObject.Zone.LocalEvents ! Terminal.StopProximityEffect(term)
      }
    } else {
      log.debug(
        s"ProximityTerminal.Unuse: target by proximity $target is not known to $term, though the unit tried to 'Unuse' it"
      )
    }
  }

  override def toString: String = term.Definition.Name
}

object ProximityTerminalControl {
  private case class TerminalAction()
}
