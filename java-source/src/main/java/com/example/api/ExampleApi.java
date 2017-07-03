package com.example.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.example.flow.ExampleFlow;
import com.example.state.IOUState;
import com.google.common.collect.ImmutableMap;
import kotlin.Pair;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.messaging.FlowProgressHandle;
import net.corda.core.node.NodeInfo;
import net.corda.core.node.services.NetworkMapCache;
import net.corda.core.node.services.Vault;
import net.corda.core.transactions.SignedTransaction;
import org.bouncycastle.asn1.x500.X500Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import static java.util.stream.Collectors.toList;
import static net.corda.client.rpc.UtilsKt.notUsed;

// This API is accessible from /api/example. All paths specified below are relative to it.
@Path("example")
public class ExampleApi {

    /**
     * RPC 服务
     */
    private final CordaRPCOps services;

    /**
     * X.500是构成全球分布式的名录服务系统的协议
     */
    private final X500Name myLegalName;

    /**
     * 公证人固定的
     */
    private final String notaryName = "CN=Controller,O=R3,OU=corda,L=London,C=UK";

    static private final Logger logger = LoggerFactory.getLogger(ExampleApi.class);

    /**
     * services
     *
     * @param services
     */
    public ExampleApi(CordaRPCOps services) {
        this.services = services;
        this.myLegalName = services.nodeIdentity().getLegalIdentity().getName();
    }

    /**
     * Returns the node's name.
     */
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, X500Name> whoami() { return ImmutableMap.of("me", myLegalName); }

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, List<X500Name>> getPeers() {
        Pair<List<NodeInfo>, Observable<NetworkMapCache.MapChange>> nodeInfo = services.networkMapUpdates();

        //不关心节点变更
        notUsed(nodeInfo.getSecond());

        return ImmutableMap.of(
            "peers",
            nodeInfo.getFirst()

                .stream()

                .map(node -> node.getLegalIdentity().getName())

                //不打印本节点和公证节点
                .filter(name -> !name.equals(myLegalName) && !(name.toString().equals(notaryName)))

                .collect(toList()));
    }

    /**
     * Displays all IOU states that exist in the node's vault.
     */
    @GET
    @Path("ious")
    @Produces(MediaType.APPLICATION_JSON)
    public List<StateAndRef<IOUState>> getIOUs() {
        Vault.Page<IOUState> vaultStates = services.vaultQuery(IOUState.class);
        return vaultStates.getStates();
    }

    /**
     * Initiates a flow to agree an IOU between two parties.
     *
     * Once the flow finishes it will have written the IOU to ledger. Both the sender and the recipient will be able to
     * see it when calling /api/example/ious on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */
    @PUT
    @Path("create-iou")
    public Response createIOU(@QueryParam("iouValue") int iouValue, @QueryParam("partyName") X500Name partyName)
        throws InterruptedException, ExecutionException {

        //取得对手方
        final Party otherParty = services.partyFromX500Name(partyName);


        if (otherParty == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }


        Response.Status status;
        String msg;
        try {

            /**
             * Start the given flow with the given arguments, returning an [Observable] with a single observation of the
             * result of running the flow. [logicType] must be annotated with [net.corda.core.flows.StartableByRPC].
             *
             * 启动指定的工作流，返回值含有Observable，必须有StartableByRPC才能允许从RPC启动工作流
             */

            FlowProgressHandle<SignedTransaction> flowHandle = services
                .startTrackedFlowDynamic(ExampleFlow.Initiator.class, iouValue, otherParty);

            flowHandle.getProgress().subscribe(evt -> System.out.printf(">> %s\n", evt));

            // The line below blocks and waits for the flow to return.
            // 下面一行代码会阻塞直到工作流返回，
            // //TODO:[depeng] 会超时吗？？？
            final SignedTransaction result = flowHandle
                .getReturnValue()
                .get();

            status = Response.Status.CREATED;
            msg = String.format("Transaction id %s committed to ledger.", result.getId());

        } catch (Throwable ex) {
            status = Response.Status.BAD_REQUEST;
            msg = ex.getMessage();
            logger.error(msg, ex);
        }

        return Response
            .status(status)
            .entity(msg)
            .build();
    }
}