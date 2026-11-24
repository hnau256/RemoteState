package org.hnau.remotestate

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor

@Serializable(with = RemoteFunctionSerializer::class)
fun interface RemoteFunction<in A, out R> {

    suspend operator fun invoke(argument: A): R
}

suspend operator fun <R> RemoteFunction<Unit, R>.invoke(): R = invoke(Unit)

class RemoteFunctionSerializer<A, R>(
    argumentSerializer: KSerializer<A>,
    resultSerializer: KSerializer<R>,
) : RemoteContextualSerializer<RemoteFunction<A, R>>(
    kClass = RemoteFunction::class,
    typeArgumentsSerializers = listOf(argumentSerializer, resultSerializer),
    descriptor = RemoteFunctionDescriptor,
)

@OptIn(ExperimentalSerializationApi::class)
val RemoteFunctionDescriptor: SerialDescriptor =
    SerialDescriptor("RemoteFunction", FunctionId.serializer().descriptor)
