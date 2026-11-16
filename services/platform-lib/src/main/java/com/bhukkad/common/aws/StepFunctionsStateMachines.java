package com.bhukkad.common.aws;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Step Functions state machine definitions for order saga orchestration.
 * <p>
 * This class contains the ASL (Amazon States Language) definitions for
 * the order processing saga with compensation/rollback handling.
 */
@Component
public class StepFunctionsStateMachines {

    /**
     * Order Processing Saga State Machine.
     * <p>
     * Orchestrates: Payment → Inventory Reservation → Order Creation → Dispatch → Delivery
     * With compensation for each step on failure.
     */
    public static final String ORDER_SAGA_DEFINITION = """
        {
          "Comment": "Bhukkad Order Processing Saga with Compensation",
          "StartAt": "ValidateOrder",
          "TimeoutSeconds": 1800,
          "States": {
            "ValidateOrder": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-validate-order",
                "Payload": {
                  "orderId.$": "$.orderId",
                  "customerId.$": "$.customerId",
                  "items.$": "$.items",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "ProcessPayment",
              "Catch": [
                {
                  "ErrorEquals": ["ValidationError"],
                  "Next": "OrderFailed",
                  "ResultPath": "$.error"
                }
              ]
            },
            "ProcessPayment": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-process-payment",
                "Payload": {
                  "orderId.$": "$.orderId",
                  "amount.$": "$.totalAmount",
                  "paymentMethod.$": "$.paymentMethod",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "ReserveInventory",
              "Catch": [
                {
                  "ErrorEquals": ["PaymentFailed", "InsufficientFunds"],
                  "Next": "CompensateValidation",
                  "ResultPath": "$.error"
                }
              ]
            },
            "ReserveInventory": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-reserve-inventory",
                "Payload": {
                  "orderId.$": "$.orderId",
                  "items.$": "$.items",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "CreateOrder",
              "Catch": [
                {
                  "ErrorEquals": ["InventoryUnavailable", "ReservationFailed"],
                  "Next": "CompensatePayment",
                  "ResultPath": "$.error"
                }
              ]
            },
            "CreateOrder": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-create-order",
                "Payload": {
                  "orderId.$": "$.orderId",
                  "customerId.$": "$.customerId",
                  "items.$": "$.items",
                  "totalAmount.$": "$.totalAmount",
                  "paymentId.$": "$.paymentId",
                  "reservationId.$": "$.reservationId",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "DispatchOrder",
              "Catch": [
                {
                  "ErrorEquals": ["OrderCreationFailed"],
                  "Next": "CompensateInventory",
                  "ResultPath": "$.error"
                }
              ]
            },
            "DispatchOrder": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-dispatch-order",
                "Payload": {
                  "orderId.$": "$.orderId",
                  "restaurantId.$": "$.restaurantId",
                  "deliveryAddress.$": "$.deliveryAddress",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "TrackDelivery",
              "Catch": [
                {
                  "ErrorEquals": ["DispatchFailed", "NoDriversAvailable"],
                  "Next": "CompensateOrder",
                  "ResultPath": "$.error"
                }
              ]
            },
            "TrackDelivery": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-track-delivery",
                "Payload": {
                  "orderId.$": "$.orderId",
                  "dispatchId.$": "$.dispatchId",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "OrderCompleted",
              "Catch": [
                {
                  "ErrorEquals": ["DeliveryFailed", "CustomerUnavailable"],
                  "Next": "CompensateDispatch",
                  "ResultPath": "$.error"
                }
              ]
            },
            "OrderCompleted": {
              "Type": "Pass",
              "Result": {"status": "COMPLETED", "message": "Order delivered successfully"},
              "End": true
            },
            "CompensateValidation": {
              "Type": "Pass",
              "Result": {"status": "VALIDATION_ROLLED_BACK"},
              "Next": "OrderFailed"
            },
            "CompensatePayment": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-refund-payment",
                "Payload": {
                  "paymentId.$": "$.paymentId",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "CompensateValidation",
              "Catch": [
                {
                  "ErrorEquals": ["States.ALL"],
                  "Next": "CompensateValidation",
                  "ResultPath": "$.compensationError"
                }
              ]
            },
            "CompensateInventory": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-release-inventory",
                "Payload": {
                  "reservationId.$": "$.reservationId",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "CompensatePayment",
              "Catch": [
                {
                  "ErrorEquals": ["States.ALL"],
                  "Next": "CompensatePayment",
                  "ResultPath": "$.compensationError"
                }
              ]
            },
            "CompensateOrder": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-cancel-order",
                "Payload": {
                  "orderId.$": "$.orderId",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "CompensateInventory",
              "Catch": [
                {
                  "ErrorEquals": ["States.ALL"],
                  "Next": "CompensateInventory",
                  "ResultPath": "$.compensationError"
                }
              ]
            },
            "CompensateDispatch": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-cancel-dispatch",
                "Payload": {
                  "dispatchId.$": "$.dispatchId",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "Next": "CompensateOrder",
              "Catch": [
                {
                  "ErrorEquals": ["States.ALL"],
                  "Next": "CompensateOrder",
                  "ResultPath": "$.compensationError"
                }
              ]
            },
            "OrderFailed": {
              "Type": "Fail",
              "Error": "OrderProcessingFailed",
              "Cause": "Order processing failed, compensation completed"
            }
          }
        }
        """;

    /**
     * Simplified Order Processing (for simpler workflows).
     */
    public static final String SIMPLE_ORDER_DEFINITION = """
        {
          "Comment": "Simple Order Processing without full saga",
          "StartAt": "ProcessOrder",
          "TimeoutSeconds": 600,
          "States": {
            "ProcessOrder": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke.waitForTaskToken",
              "Parameters": {
                "FunctionName": "bhukkad-process-order-simple",
                "Payload": {
                  "orderId.$": "$.orderId",
                  "customerId.$": "$.customerId",
                  "items.$": "$.items",
                  "taskToken.$": "$$.Task.Token"
                }
              },
              "End": true,
              "Catch": [
                {
                  "ErrorEquals": ["States.ALL"],
                  "Next": "OrderFailed",
                  "ResultPath": "$.error"
                }
              ]
            },
            "OrderFailed": {
              "Type": "Fail",
              "Error": "OrderProcessingFailed",
              "Cause": "Order processing failed"
            }
          }
        }
        """;

    /**
     * Loyalty Points Recalculation State Machine (scheduled).
     */
    public static final String LOYALTY_RECALCULATION_DEFINITION = """
        {
          "Comment": "Scheduled Loyalty Points Recalculation",
          "StartAt": "FetchActiveCustomers",
          "TimeoutSeconds": 3600,
          "States": {
            "FetchActiveCustomers": {
              "Type": "Task",
              "Resource": "arn:aws:states:::lambda:invoke",
              "Parameters": {
                "FunctionName": "bhukkad-fetch-active-customers"
              },
              "Next": "ProcessCustomerBatch"
            },
            "ProcessCustomerBatch": {
              "Type": "Map",
              "MaxConcurrency": 10,
              "Iterator": {
                "StartAt": "CalculateLoyalty",
                "States": {
                  "CalculateLoyalty": {
                    "Type": "Task",
                    "Resource": "arn:aws:states:::lambda:invoke",
                    "Parameters": {
                      "FunctionName": "bhukkad-calculate-loyalty",
                      "Payload": {
                        "customerId.$": "$.customerId"
                      }
                    },
                    "End": true
                  }
                }
              },
              "ItemsPath": "$.customers",
              "End": true
            }
          }
        }
        """;

    /**
     * Returns all state machine definitions as a map.
     */
    public Map<String, String> getAllDefinitions() {
        return Map.of(
                "OrderSaga", ORDER_SAGA_DEFINITION,
                "SimpleOrder", SIMPLE_ORDER_DEFINITION,
                "LoyaltyRecalculation", LOYALTY_RECALCULATION_DEFINITION
        );
    }
}