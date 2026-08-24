package com.bhukkad.grpc;

import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.grpc.proto.GetOrderStatusRequest;
import com.bhukkad.grpc.proto.OrderInternalServiceGrpc;
import com.bhukkad.grpc.proto.OrderStatusSnapshot;
import com.bhukkad.service.OrderService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * Internal gRPC surface for order status lookups (FEATURE #9).
 *
 * <p>Sibling services (pricing, settlement, analytics) call this instead of the
 * REST API: protobuf halves payload size versus JSON and avoids HTTP overhead on
 * the hot path. Authorization is transport-level — the gRPC port is cluster-only
 * and never exposed through nginx.</p>
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class GrpcOrderStatusService extends OrderInternalServiceGrpc.OrderInternalServiceImplBase {

    private final OrderService orderService;

    @Override
    public void getOrderStatus(GetOrderStatusRequest request,
                               StreamObserver<OrderStatusSnapshot> responseObserver) {
        try {
            OrderResponse order = orderService.getOrderById(request.getOrderId());
            responseObserver.onNext(OrderStatusSnapshot.newBuilder()
                    .setOrderId(order.getId() != null ? order.getId() : 0)
                    .setOrderNumber(order.getOrderNumber() != null ? order.getOrderNumber() : "")
                    .setStatus(order.getStatus() != null ? order.getStatus() : "")
                    .setTotalAmount(order.getTotalAmount() != null ? order.getTotalAmount() : 0.0)
                    .setCustomerId(order.getCustomerId() != null ? order.getCustomerId() : 0)
                    .setRestaurantId(order.getRestaurantId() != null ? order.getRestaurantId() : 0)
                    .setCreatedAtIso(order.getCreatedAt() != null ? order.getCreatedAt().toString() : "")
                    .build());
            responseObserver.onCompleted();
        } catch (ResourceNotFoundException notFound) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Order " + request.getOrderId() + " not found")
                    .asRuntimeException());
        } catch (Exception ex) {
            log.error("gRPC getOrderStatus failed | orderId={}", request.getOrderId(), ex);
            responseObserver.onError(Status.INTERNAL.withCause(ex).asRuntimeException());
        }
    }
}
