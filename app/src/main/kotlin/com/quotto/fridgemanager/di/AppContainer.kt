package com.quotto.fridgemanager.di

import android.content.Context
import com.quotto.fridgemanager.data.local.EmptyInventoryRepository
import com.quotto.fridgemanager.data.local.InventoryDatabase
import com.quotto.fridgemanager.data.local.RoomInventoryRepository
import com.quotto.fridgemanager.data.auth.FirebaseAuthComposition
import com.quotto.fridgemanager.domain.auth.AuthCoordinator
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.presentation.inventory.InventoryPresenter
import com.quotto.fridgemanager.presentation.inventory.IngredientUpdatePresenter
import com.quotto.fridgemanager.presentation.inventory.AiUpdateCandidatePresenter
import com.quotto.fridgemanager.presentation.registration.RegistrationPresenter
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewPresenter
import com.quotto.fridgemanager.BuildConfig
import com.quotto.fridgemanager.data.remote.AuthorizedAnalysisApiClient
import com.quotto.fridgemanager.data.remote.UrlConnectionAnalysisHttpTransport
import com.quotto.fridgemanager.domain.analysis.AnalysisClient
import com.quotto.fridgemanager.data.deletion.AndroidDataDeletionGateway
import com.quotto.fridgemanager.presentation.settings.DataDeletionCoordinator
import com.quotto.fridgemanager.presentation.settings.DataDeletionGateway

/** アプリ全体の依存を生成するComposition Root。 */
interface AppContainer {
    val inventoryRepository: InventoryRepository
    val inventoryPresenter: InventoryPresenter
    val registrationPresenter: RegistrationPresenter
    val ingredientUpdatePresenter: IngredientUpdatePresenter
    val candidateReviewPresenter: CandidateReviewPresenter
    val aiUpdateCandidatePresenter: AiUpdateCandidatePresenter
    val authCoordinator: AuthCoordinator
    val analysisApiClient: AnalysisClient?
    val dataDeletionCoordinator: DataDeletionCoordinator
}

class DefaultAppContainer(
    inventoryRepository: InventoryRepository? = null,
    context: Context? = null,
) : AppContainer {
    private val database = context?.let(InventoryDatabase::create)
    override val inventoryRepository: InventoryRepository = inventoryRepository
        ?: database?.let(::RoomInventoryRepository)
        ?: EmptyInventoryRepository()
    override val inventoryPresenter: InventoryPresenter = InventoryPresenter(this.inventoryRepository)
    override val registrationPresenter: RegistrationPresenter = RegistrationPresenter(this.inventoryRepository)
    override val ingredientUpdatePresenter = IngredientUpdatePresenter(this.inventoryRepository)
    override val candidateReviewPresenter = CandidateReviewPresenter(this.inventoryRepository)
    override val aiUpdateCandidatePresenter = AiUpdateCandidatePresenter(this.inventoryRepository)
    override val authCoordinator: AuthCoordinator = context?.let(FirebaseAuthComposition::create)
        ?: FirebaseAuthComposition.createUnavailable()
    override val analysisApiClient: AnalysisClient? = BuildConfig.ANALYSIS_API_BASE_URL
        .takeIf(String::isNotBlank)
        ?.let { AuthorizedAnalysisApiClient(authCoordinator, UrlConnectionAnalysisHttpTransport(it)) }
    override val dataDeletionCoordinator = DataDeletionCoordinator(
        if (context != null && database != null) {
            AndroidDataDeletionGateway(context, database, authCoordinator)
        } else {
            object : DataDeletionGateway {
                override suspend fun deleteLocalInventory() = error("Local storage is unavailable")
                override suspend fun deleteTemporaryImages() = error("Temporary storage is unavailable")
                override suspend fun deleteAnonymousUser() = error("Firebase is unavailable")
            }
        },
    )
}
