package com.stefano.corda.deposits.flow

import co.paralleluniverse.fibers.Suspendable
import com.stefano.corda.deposits.ArbitratorDeduction
import com.stefano.corda.deposits.DepositContract
import com.stefano.corda.deposits.DepositState
import com.stefano.corda.deposits.flow.FundDepositFlow.getStateAndRefByLinearId
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.asset.PartyAndAmount
import java.time.Instant


object ArbitrateAndRefundFlow {


    @InitiatingFlow
    @StartableByRPC
    class Initiator(val depositId: UniqueIdentifier,
                    val deductions: List<ArbitratorDeduction>) : FlowLogic<SignedTransaction>() {

        override val progressTracker = tracker()

        companion object {
            object FINDING_STATE : ProgressTracker.Step("Locating unfunded deposit to fund")
            object UPDATING_STATE : ProgressTracker.Step("Verifying contract constraints.")
            object ADDING_CASH_FOR_TENANT : ProgressTracker.Step("Asking for cash");
            object ADDING_CASH_FOR_LANDLORD : ProgressTracker.Step("Asking for cash");
            object VERIFYING_CASH_MOVEMENT : ProgressTracker.Step("Checking Cash");
            object DONE : ProgressTracker.Step("Done");

            object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    FINDING_STATE,
                    UPDATING_STATE,
                    ADDING_CASH_FOR_TENANT,
                    ADDING_CASH_FOR_LANDLORD,
                    VERIFYING_CASH_MOVEMENT,
                    DONE
            )
        }

        @Suspendable
        override fun call(): SignedTransaction {
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            progressTracker.currentStep = FINDING_STATE;

            val refAndState = serviceHub.getStateAndRefByLinearId<DepositState>(linearId = depositId);


            progressTracker.currentStep = UPDATING_STATE;

            val copy = refAndState.state.data.copy(contestedDeductions = deductions, refundedAt = Instant.now());
            progressTracker.currentStep = ADDING_CASH_FOR_TENANT;


            val arbitrateCommand = Command(
                    DepositContract.Commands.Arbitrate(copy.propertyId),
                    listOf(copy.landlord, copy.tenant, copy.issuer).map { it.owningKey }
            )

            var txBuilder = TransactionBuilder(notary)
                    .addInputState(refAndState)
                    .addOutputState(copy, DepositContract.DEPOSIT_CONTRACT_ID)
                    .addCommand(arbitrateCommand)


            val deductionsTotal = (copy.contestedDeductions?.sumByLong { it.amount.quantity })
            var tenantAmount = copy.depositAmount;
            deductionsTotal?.let { toSubstract ->
                tenantAmount = tenantAmount.minus(Amount(toSubstract, copy.depositAmount.token))
            }
            val landlordAmount = copy.depositAmount.minus(tenantAmount);
            progressTracker.currentStep = ADDING_CASH_FOR_LANDLORD;

            txBuilder = Cash.generateSpend(serviceHub, txBuilder, listOf(
                    PartyAndAmount(copy.landlord, landlordAmount),
                    PartyAndAmount(copy.tenant, tenantAmount))).first

            //sync cash input states
            val landlordSession = initiateFlow(copy.landlord)
            val tenantSession = initiateFlow(copy.tenant)
            val cashInputsToSync = txBuilder.inputStates()
                    .map { serviceHub.toStateAndRef<ContractState>(it) }
                    .filter { it.state.data is FungibleAsset<*> }


            subFlow(SendStateAndRefFlow(landlordSession, cashInputsToSync));
            subFlow(SendStateAndRefFlow(tenantSession, cashInputsToSync));

            //sync identities for change
            subFlow(IdentitySyncFlow.Send(landlordSession, txBuilder.toWireTransaction(serviceHub)))
            subFlow(IdentitySyncFlow.Send(tenantSession, txBuilder.toWireTransaction(serviceHub)))


            txBuilder.verify(serviceHub)
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)


            progressTracker.currentStep = VERIFYING_CASH_MOVEMENT
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(landlordSession, tenantSession),
                    DepositIssueFlow.Initiator.Companion.GATHERING_SIGS.childProgressTracker()))

            progressTracker.currentStep = DONE
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))

        }
    }

    @InitiatingFlow
    @InitiatedBy(Initiator::class)
    class Responder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        override val progressTracker: ProgressTracker = tracker()

        companion object {
            object RECEIVING_STATES : ProgressTracker.Step("Receiving transactions for cash")
            object RECEIVING_IDENTITIES : ProgressTracker.Step("Receiving identities for cash")
            object DONE : ProgressTracker.Step("Finished")

            fun tracker() = ProgressTracker(
                    RECEIVING_STATES,
                    RECEIVING_IDENTITIES,
                    DONE
            )
        }

        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = RECEIVING_STATES
            subFlow(ReceiveStateAndRefFlow<FungibleAsset<*>>(counterpartySession))
            progressTracker.currentStep = RECEIVING_IDENTITIES
            subFlow(IdentitySyncFlow.Receive(counterpartySession))
            val flow = object : SignTransactionFlow(counterpartySession) {
                @Suspendable
                override fun checkTransaction(stx: SignedTransaction) {
                    //all checks delegated to contract
                }
            }
            val stx = subFlow(flow)
            val commit = waitForLedgerCommit(stx.id)
            progressTracker.currentStep = DONE
            return commit
        }
    }

}



