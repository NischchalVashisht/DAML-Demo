// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.knoldus

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.digitalasset.api.util.TimeProvider
import com.digitalasset.grpc.adapter.AkkaExecutionSequencerPool
import com.digitalasset.ledger.api.refinements.ApiTypes.{ApplicationId, WorkflowId}
import com.digitalasset.ledger.api.v1.ledger_offset.LedgerOffset
import com.digitalasset.ledger.client.LedgerClient
import com.digitalasset.ledger.client.configuration.{
  CommandClientConfiguration,
  LedgerClientConfiguration,
  LedgerIdRequirement
}
import com.knoldus.ClientUtil.workflowIdFromParty
import com.knoldus.DecodeUtil.{decodeArchived, decodeCreated}
import com.knoldus.FutureUtil.toFuture
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

// <doc-ref:imports>
import com.digitalasset.ledger.client.binding.{Primitive => P}
import com.knoldus.{Iou => M}                  // File Iou -> Template
// </doc-ref:imports>

object IouMain extends App with StrictLogging {

  if (args.length != 2) {
    logger.error("Usage: LEDGER_HOST LEDGER_PORT")
    System.exit(-1)
  }

  private val ledgerHost = args(0)                                                                 //
  private val ledgerPort = args(1).toInt

  // <doc-ref:firstParty-definition>
  private val firstParty = P.Party("Alice")

  // <doc-ref:new-owner-definition>
  private val secondParty = P.Party("Bob")
  // </doc-ref:new-owner-definition>

  private val asys = ActorSystem()
  private val amat = Materializer(asys)
  private val aesf = new AkkaExecutionSequencerPool("clientPool")(asys)

  private def shutdown(): Unit = {
    logger.info("Shutting down...")
    Await.result(asys.terminate(), 10.seconds)
    ()
  }


  private implicit val ec: ExecutionContext = asys.dispatcher

  private val applicationId = ApplicationId("Example")

  private val timeProvider = TimeProvider.Constant(Instant.EPOCH)

  // <doc-ref:ledger-client-configuration>
  private val clientConfig = LedgerClientConfiguration(
    applicationId = ApplicationId.unwrap(applicationId),
    ledgerIdRequirement = LedgerIdRequirement("", enabled = false),
    commandClient = CommandClientConfiguration.default,
    sslContext = None,
    token = None
  )
  // </doc-ref:ledger-client-configuration>

  private val clientF: Future[LedgerClient] =
    LedgerClient.singleHost(ledgerHost, ledgerPort, clientConfig)(ec, aesf)

  private val clientUtilF: Future[ClientUtil] =
    clientF.map(client => new ClientUtil(client, applicationId, 30.seconds, timeProvider))

  private val offset0F: Future[LedgerOffset] = clientUtilF.flatMap(_.ledgerEnd)

  private val issuerWorkflowId: WorkflowId = workflowIdFromParty(firstParty)                                                  // Why workflow id?

  // <doc-ref:iou-contract-instance>
  val iou = M.Contact(                                                                                           //Contract Format
    owner = firstParty,
    telephone = "12345"
    )
  // </doc-ref:iou-contract-instance>

  val issuerFlow: Future[Unit] = for {
    clientUtil <- clientUtilF
    offset0 <- offset0F
    _ = logger.info(s"Client API initialization completed, Ledger ID: ${clientUtil.toString}")

    // <doc-ref:submit-iou-create-command>
    createCmd = iou.create                                                                                // Creating contract
    _ <- clientUtil.submitCommand(firstParty, issuerWorkflowId, createCmd)
    _ = logger.info(s"$firstParty created IOU: $iou")
    _ = logger.info(s"$firstParty sent create command: $createCmd")
    // </doc-ref:submit-iou-create-command>

    tx0 <- clientUtil.nextTransaction(firstParty, offset0)(amat)                                              // Transaction confirmation transaction
    _ = logger.info(s"$firstParty received transaction: $tx0")
    iouContract <- toFuture(decodeCreated[M.Contact](tx0))                                                    // Fetching active contract (decodeCreated) from transaction (tx0)
    _ = logger.info(s"$firstParty received contract: $iouContract")

    offset1 <- clientUtil.ledgerEnd

     //<doc-ref:iou-exercise-transfer-cmd>
    exerciseCmd = iouContract.contractId.exerciseUpdateTelephone(actor = firstParty, newTelephone = "00000")        // iouconarct -> fetched , Format = exercise+choice
    // </doc-ref:iou-exercise-transfer-cmd>
    _ <- clientUtil.submitCommand(firstParty, issuerWorkflowId, exerciseCmd)                                  // Submitting exercise command
    _ = logger.info(s"$firstParty sent exercise command: $exerciseCmd")
    _ = logger.info(s"$firstParty transferred IOU: $iouContract to: $secondParty")

    tx1 <- clientUtil.nextTransaction(firstParty, offset1)(amat)                                             // Fetching transaction
    _ = logger.info(s"$firstParty received final transaction: $tx1")
    archivedIouContractId <- toFuture(decodeArchived[M.Contact](tx1)): Future[P.ContractId[M.Contact]]           // Fetching Archived contract from transaction (tx1)
    _ = logger.info(
      s"$firstParty received Archive Event for the original IOU contract ID: $archivedIouContractId")
    _ <- Future(assert(iouContract.contractId == archivedIouContractId))

  } yield ()

  val returnCodeF: Future[Int] = issuerFlow.transform {
    case Success(_) =>
      logger.info("IOU flow completed.")
      Success(0)
    case Failure(e) =>
      logger.error("IOU flow completed with an error", e)
      Success(1)
  }

  val returnCode: Int = Await.result(returnCodeF, 10.seconds)
  shutdown()
  System.exit(returnCode)
}
