package com.example.contract;

import java.util.stream.Collectors;

import com.example.state.IOUState;
import kotlin.jvm.functions.Function1;
import net.corda.core.contracts.AuthenticatedObject;
import net.corda.core.contracts.CommandData;
import net.corda.core.contracts.Contract;
import net.corda.core.contracts.Requirements;
import net.corda.core.contracts.TransactionForContract;
import net.corda.core.crypto.SecureHash;
import net.corda.core.identity.AbstractParty;

import static net.corda.core.contracts.ContractsDSL.requireSingleCommand;
import static net.corda.core.contracts.ContractsDSL.requireThat;

/**
 * A implementation of a basic smart contract in Corda.
 *
 * This contract enforces rules regarding the creation of a valid [IOUState], which in turn encapsulates an [IOU].
 *
 * For a new [IOU] to be issued onto the ledger, a transaction is required which takes:
 * - Zero input states.
 * - One output state: the new [IOU].
 * - An Create() command with the public keys of both the sender and the recipient.
 *
 * All contracts must sub-class the [Contract] interface.
 */
public class IOUContract implements Contract {
    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    @Override
    public void verify(TransactionForContract tx) {
        final AuthenticatedObject<Commands.Create> command = requireSingleCommand(tx.getCommands(), Commands.Create.class);

        Function1<Requirements, Object> body = require -> {

            // 检验IOU合约输入资产必须为空
            // Generic constraints around the IOU transaction.
            require.using("No inputs should be consumed when issuing an IOU.",
                tx.getInputs().isEmpty());

            // 检验IOU合约输出资产必须唯一
            require.using("Only one output state should be created.",
                tx.getOutputs().size() == 1);


            final IOUState out = (IOUState)tx.getOutputs().get(0);

            // 验证发送人和接收人不可以一样
            require.using("The sender and the recipient cannot be the same entity.",
                out.getSender() != out.getRecipient());


            // 验证所有参与方的签名
            require.using("All of the participants must be signers.",
                command.getSigners().containsAll(
                    out.getParticipants().stream().map(AbstractParty::getOwningKey).collect(Collectors.toList())));

            // 借款金额大于0
            // IOU-specific constraints.
            require.using("The IOU's value must be non-negative.",
                out.getIOU().getValue() > 0);

            return null;
        };

        requireThat(body);
    }

    /**
     * This contract only implements one command, Create.
     */
    public interface Commands extends CommandData {

        /**
         *
         * 这个合约只有一个Create命令
         *
         */
        class Create implements Commands {}
    }

    /** This is a reference to the underlying legal contract template and associated parameters. */
    private final SecureHash legalContractReference = SecureHash.sha256("IOU contract template and params");
    @Override public final SecureHash getLegalContractReference() { return legalContractReference; }
}