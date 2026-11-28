package org.hnau.remotestate.test.app

import io.ktor.client.HttpClient
import org.hnau.commons.app.model.app.RootModelFactory
import org.hnau.commons.app.model.theme.color.Hue
import org.hnau.commons.app.projector.app.RootProjectorFactory
import org.hnau.remotestate.test.app.model.TestRootModel
import org.hnau.remotestate.test.app.model.TestServerController
import org.hnau.remotestate.test.app.model.impl
import org.hnau.remotestate.test.app.projector.Localization
import org.hnau.remotestate.test.app.projector.TestRootProjector
import org.hnau.remotestate.test.app.projector.impl

fun createTestRootModelFactory(
    httpClient: HttpClient,
    serverUrl: String,
    serverController: TestServerController,
): RootModelFactory<TestRootModel.Skeleton, TestRootModel> = RootModelFactory(
    skeletonSerializer = TestRootModel.Skeleton.serializer(),
    createDefaultSkeleton = { TestRootModel.Skeleton() },
    createRootModel = { scope, skeleton ->
        TestRootModel(
            scope = scope,
            dependencies = TestRootModel.Dependencies.impl(
                httpClient = httpClient,
                serverUrl = serverUrl,
                serverController = serverController,
            ),
            skeleton = skeleton,
        )
    },
    extractGoBackHandler = TestRootModel::goBackHandler,
)

val TestRootProjectorFactory: RootProjectorFactory<TestRootModel, TestRootProjector> = RootProjectorFactory(
    fallbackHue = Hue(200),
    createRootProjector = { scope, model, appUIContext ->
        TestRootProjector(
            scope = scope,
            model = model,
            dependencies = TestRootProjector.Dependencies.impl(
                localization = Localization.default,
                bubblesShower = appUIContext.bubblesShower,
            ),
        )
    },
    content = { rootProjector, contentPadding ->
        rootProjector.Content(
            contentPadding = contentPadding,
        )
    },
)
