package com.stefano.corda.deposits

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash

open class DepositContract : Contract {
    companion object {
        @JvmStatic
        val DEPOSIT_CONTRACT_ID = "com.stefano.corda.deposits.DepositContract"
    }

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()

        when (command.value){
            is Commands.Create -> {
                requireThat {

                    val depositInputs =  tx.inputsOfType<DepositState>()
                    "there must be no deposit input states, this is the initial one" using (depositInputs.isEmpty())
                    "there must be no input states at all" using tx.inputStates.isEmpty()

                    val depositStates = tx.outputsOfType<DepositState>()
                    "there must be only one deposit output state" using (depositStates.size == 1)
                    "there must be only one output state" using (tx.outputs.size == 1)

                    val outputState = depositStates.first()
                    "the landlord and tenants must be different" using (outputState.landlord != outputState.tenant)
                }
            }
            is Commands.Fund -> {
                requireThat {

                    val depositInputStates =  tx.inputsOfType<DepositState>()
                    val cashInputs =  tx.inputsOfType<Cash.State>()
                    "there must be one incoming deposit input state" using (depositInputStates.size == 1)
                    "there must be a cash input state" using (cashInputs.size == 1)

                    val depositOutputStates = tx.outputsOfType<DepositState>()
                    val cashOutputStates = tx.outputsOfType<Cash.State>()
                    "there must be only one deposit output state" using (depositInputStates.size == 1)

                    val depositOutputState = depositOutputStates.first()
                    val depositInputState = depositInputStates.first()
                    val cashOutput = cashOutputStates.first()

                    "only deposited amount can change on the deposit state" using
                            depositInputState.isEqualToExcluding(depositOutputState, setOf(DepositState::amountDeposited))

                    "the cash amount sent must equal the deposited amount" using (depositOutputState.amountDeposited?.quantity == cashOutput.amount.quantity)
                    "the cash amount must be sent to the deposit backing scheme" using (cashOutput.owner == depositOutputState.issuer)
                }
            }

            is Commands.TenantDeduct -> {

                requireThat{
                    val inputDeposit = tx.inputsOfType<DepositState>().first()
                    val outputDeposit = tx.outputsOfType<DepositState>().first()

                    "states must be identical except for deduction fields" using inputDeposit.isEqualToExcluding(outputDeposit, setOf(DepositState::acceptedDeductions, DepositState::tenantDeductions))

                    "all tenant supplied deductions must be included in original landlord deductions apart from amount" using
                    outputDeposit.landlordDeductions!!.containsAllExcluding(outputDeposit.tenantDeductions!!, setOf(Deduction::deductionAmount))
                }

            }
        }
    }



    /**
     * This contract only implements one command, Create.
     */
    interface Commands : CommandData {
        data class Create(val propertyId: String) : Commands
        data class CoSign(val propertyId: String) : Commands
        data class Fund(val propertyId: String) : Commands
        data class LandlordDeduct(val propertyId: String) : Commands
        data class TenantDeduct(val propertyId: String) : Commands
        data class RequestRefund(val propertyId: String) : Commands
        data class Refund(val propertyId: String) : Commands
    }
}
