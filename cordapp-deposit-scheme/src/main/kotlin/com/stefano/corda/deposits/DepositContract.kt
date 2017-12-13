package com.stefano.corda.deposits

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction

open class DepositContract : Contract {
    companion object {
        @JvmStatic
        val IOU_CONTRACT_ID = "com.stefano.corda.deposits.DepositContract"
    }

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands.Create>()
        requireThat {
            // Generic constraints around the IOU transaction.
            "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
//            val out = tx.outputsOfType().single()
//            "The lender and the borrower cannot be the same entity." using (out.lender != out.borrower)
//            "All of the participants must be signers." using (command.signers.containsAll(out.participants.map { it.owningKey }))

            // IOU-specific constraints.
//            "The IOU's value must be non-negative." using (out.value > 0)
        }
    }

    /**
     * This contract only implements one command, Create.
     */
    interface Commands : CommandData {
        data class Create(val propertyId: String) : Commands
        data class CoSign(val propertyId: String) : Commands
        data class Fund(val propertyId: String) : Commands
        data class Deduct(val propertyId: String) : Commands
        data class Refund(val propertyId: String) : Commands
    }
}
